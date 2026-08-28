package wikidata.explore.workbench;

import wikidata.WikidataIds;

import objectview.utils.swing.GridBagUtils;

import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.ClassSourceBindings;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.StatementClassSource;
import datasource.api.SourceBindingSlot;
import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.logical.DiscoverSubtypesQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.api.WikidataApiClient;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import wikidata.ui.WikidataLinks;

public class ClassSourcePanel extends JPanel {

    private final JComboBox<ClassSearchQuery.Mode> searchModeBox =
            new JComboBox<>(ClassSearchQuery.Mode.values());

    private GeneratedClassModel clazz;
    private SwingQueryRunner queryRunner;

    private Consumer<String> log = s -> {};
    private Consumer<Void> afterChange = v -> {};

    private final JLabel titleLabel = new JLabel("Class");

    private final JTextField classNameField = new JTextField(18);
    private final JTextField aliasField = new JTextField(18);

    // "Extends" base class: this class inherits the base's fields/membership
    // and adds its own (see GeneratedClassModel.effectiveFields). Blank = none.
    private static final String NO_BASE = "(none)";
    private final JComboBox<String> baseClassBox = new JComboBox<>();
    private Supplier<List<String>> baseClassCandidates = List::of;
    // Subclass discriminator: a (property, value) pair narrowing the inherited
    // membership to instances that also have ?value wdt:<pid> wd:<qid> (Person =
    // nominee membership AND P31=human). Property defaults to P31 but can be any.
    private final JTextField discriminatorPidField = new JTextField("P31", 5);
    private final JTextField discriminatorQidField = new JTextField(8);
    private final JLabel discriminatorLabel = new JLabel(" ");
    // STATEMENT reification: instances of this class are the statements of the
    // relation property on each member of the named class (e.g. Nomination = the
    // P1411 statements of Oscarnominations). Its fields draw from the value (ps:)
    // and qualifiers (pq:, set per-field). Blank = a normal class.
    private final JTextField statementSourceField = new JTextField(12);

    private final JTextField typeQidField = new JTextField(10);
    private final JLabel typeLabel = new JLabel("(not selected)");
    // Row label for the source-QID field. Reads "Wikidata type/class:" only when
    // the relation is P31 (instance of); for any other relation the source QID is
    // the relation's TARGET (e.g. P166 → the award won), not a type — so the label
    // adapts to avoid the "is this a class?" confusion.
    private final JLabel typeRowLabel = new JLabel("Wikidata type/class:");
    // The membership relation property: P31 (instance of) by default, but any
    // property — e.g. P166 (award received) for "won this award".
    private final JTextField relationPidField = new JTextField(6);
    private final JButton findRelationButton = new JButton("Find…");
    // Resolved label of the relation property (e.g. "nominated for"), shown next
    // to the PID and clickable to open the property's Wikidata page.
    private final JLabel relationLabel = new JLabel(" ");
    // Lazily-created; the property/item name search uses the Wikidata API.
    private WikidataApiClient api;
    private final JTextField additionalTypesField = new JTextField(14);
    private final JTextField excludeTypesField = new JTextField(14);
    private final JButton discoverTypesButton = new JButton("Discover subtypes");
    // Discover the membership targets from a parent's "has part(s)" relation —
    // e.g. Academy Awards (Q19020) wdt:P527 → its award categories — so the
    // multi-QID membership is data-driven instead of a hand-pasted QID list.
    private final JButton fromPartsButton = new JButton("From parts…");
    private final JCheckBox notableOnlyBox =
            new JCheckBox("Notable only (require Wikipedia article)");

    // Class-level ranking: keep the top `limit` instances by this measure.
    private static final String NO_RANK = "(none — by name)";
    private static final String RANK_NOTABILITY = "Notability (sitelinks)";
    private final JComboBox<String> rankByBox = new JComboBox<>();
    private final JCheckBox rankDescBox = new JCheckBox("highest first", true);

    // Explicit instance QIDs (curated set, e.g. seeded from a WikiProject /
    // category). With no type above, these alone are the class's instances.
    private final JTextArea seedQidsArea = new JTextArea(3, 18);

    private final JTextField searchTextField = new JTextField(18);
    private final JButton searchTypeButton = new JButton("Search");

    private final SearchTableModel searchModel = new SearchTableModel();
    private final JTable searchTable = new JTable(searchModel);
    private final JButton useSelectedButton = new JButton("Use selected");

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(200, 1, 1_000_000, 10));

    private final JCheckBox requireLabelBox =
            new JCheckBox("Require label", true);

    private final JTextField langField =
            new JTextField(wikidata.WikidataLanguageDefaults.CODE, 4);

    // --- Identity & label (canonicalization) ---
    private static final String KIND_ENTITY = "Source entity (id + label)";
    private static final String KIND_DERIVED = "Derived (key + field/template)";
    private static final String DN_LABEL = "Label";
    private static final String DN_FIELD = "Field";
    private static final String DN_TEMPLATE = "Template";

    /** States the regime rather than offering it: it is a consequence, not a choice. */
    private final javax.swing.JLabel canonicalKindLabel = new javax.swing.JLabel();
    private final javax.swing.JLabel canonicalSourcesLabel = new javax.swing.JLabel();
    private final JComboBox<String> displayNameModeBox =
            new JComboBox<>(new String[]{DN_LABEL, DN_FIELD, DN_TEMPLATE});
    private final JComboBox<String> displayNameFieldBox = new JComboBox<>();
    private final JTextField displayNameTemplateField = new JTextField(18);
    private final JTextField keyFieldsField = new JTextField(18);
    private final JLabel canonicalHint = new JLabel(" ");

    private final JButton applyButton =
            new JButton("Apply class source");

    private final JLabel summaryLabel =
            new JLabel(" ");

    public ClassSourcePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    /**
     * One-shot: the search button's action listener binds to the first
     * runner's workflow, so later calls are ignored.
     */
    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) {
            return;
        }

        this.queryRunner = queryRunner;
        wireQuery();
        updateSearchButtonState();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    /** Supplies the class names this class may extend (the project's classes;
     *  the editing class itself is filtered out). */
    public void baseClassCandidates(Supplier<List<String>> candidates) {
        this.baseClassCandidates = candidates == null ? List::of : candidates;
    }

    public void edit(GeneratedClassModel clazz) {
        this.clazz = clazz;

        if (clazz == null) {
            clear();
            return;
        }

        FieldSourceMapping m = clazz.instanceMapping();

        titleLabel.setText("Class: " + clazz.className());

        classNameField.setText(clazz.className());
        aliasField.setText(clazz.alias());
        searchTextField.setText(clazz.className());

        populateBaseClasses();

        typeQidField.setText(m.sourceQid());
        typeLabel.setText(m.displaySource());
        relationPidField.setText(m.propertyPid().isBlank() ? "P31" : m.propertyPid());
        // setText above fires the listener that blanks relationLabel — restore the
        // stored label afterwards so a saved relation shows its name on load.
        relationLabel.setText(m.propertyLabel() == null ? " " : m.propertyLabel());
        additionalTypesField.setText(String.join(" ", m.additionalTypeQids()));
        excludeTypesField.setText(String.join(" ", m.excludedTypeQids()));

        limitSpinner.setValue(Math.max(1, m.limit()));
        requireLabelBox.setSelected(m.requireLabel());
        notableOnlyBox.setSelected(m.requireSitelink());
        langField.setText(m.labelLanguage());
        seedQidsArea.setText(String.join(" ", clazz.seedQids()));
        populateRankBy(m);
        loadCanonical();

        updateSummary();
        updateSearchButtonState();
    }

    // Fill the extends combo with the other classes (excluding self), selecting
    // this class's current base.
    private void populateBaseClasses() {
        baseClassBox.removeAllItems();
        baseClassBox.addItem(NO_BASE);
        String self = clazz == null ? "" : clazz.className();
        for (String name : baseClassCandidates.get()) {
            if (name != null && !name.isBlank() && !name.equals(self)) {
                baseClassBox.addItem(name);
            }
        }
        String base = clazz == null ? "" : clazz.baseClassName();
        baseClassBox.setSelectedItem(base == null || base.isBlank() ? NO_BASE : base);
        discriminatorPidField.setText(clazz == null ? "P31" : clazz.effectiveDiscriminatorPid());
        discriminatorQidField.setText(clazz == null ? "" : clazz.discriminatorQid());
        StatementClassSource statement = clazz == null ? null : clazz.statementSource();
        statementSourceField.setText(statement == null ? "" : statement.sourceClassName());
    }

    // Rank-by options: none, notability, and the class's sortable (number/date)
    // fields by name.
    private void populateRankBy(FieldSourceMapping m) {
        rankByBox.removeAllItems();
        rankByBox.addItem(NO_RANK);
        rankByBox.addItem(RANK_NOTABILITY);
        if (clazz != null) {
            for (GeneratedFieldModel f : clazz.fields()) {
                if (f != null && !f.isNameField()
                        && (f.type() == FieldType.NUMBER || f.type() == FieldType.DATE)) {
                    rankByBox.addItem(f.name());
                }
            }
        }
        String rb = m.rankBy();
        if (FieldSourceMapping.RANK_BY_SITELINKS.equals(rb)) {
            rankByBox.setSelectedItem(RANK_NOTABILITY);
        } else if (rb != null && !rb.isBlank()) {
            rankByBox.setSelectedItem(rb);
        } else {
            rankByBox.setSelectedItem(NO_RANK);
        }
        rankDescBox.setSelected(m.rankDescending());
    }

    public void useSourceQid(String qid, String label) {
        if (clazz == null) {
            return;
        }

        clazz.instanceMapping().sourceQid(qid);
        clazz.instanceMapping().sourceLabel(label);

        typeQidField.setText(clazz.instanceMapping().sourceQid());
        typeLabel.setText(clazz.instanceMapping().displaySource());

        updateSummary();
        afterChange.accept(null);
    }

    public RuleNode toRuleNode() {
        return clazz == null ? null : RuleTreeCompiler.compileClass(clazz);
    }

    public void applyEdits() {
        apply();
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        GridBagUtils.wideRow(form, y++, titleLabel);

        JLabel question =
                new JLabel("How do we find instances of this class?");
        question.setFont(question.getFont().deriveFont(Font.ITALIC));
        GridBagUtils.wideRow(form, y++, question);

        GridBagUtils.labeledRow(form, c, y++, "Class name:", classNameField);

        aliasField.setToolTipText("<html>Display alias: what the UI shows for "
                + "this class instead of its name. Pure presentation — the class "
                + "name stays the identity everything references, so aliasing "
                + "never breaks the model.</html>");
        GridBagUtils.labeledRow(form, c, y++, "Alias:", aliasField);

        baseClassBox.setToolTipText("<html>Extend another class: this class "
                + "inherits the base's fields and membership and adds its own "
                + "(a subclass field with the same name overrides). Lets a shared "
                + "base (e.g. Person) be reused and extended per domain.</html>");
        GridBagUtils.labeledRow(form, c, y++, "Extends:", baseClassBox);

        discriminatorPidField.setToolTipText("Discriminator property — defaults to "
                + "P31 (instance of); set another relation to subclass on a "
                + "non-type axis.");
        discriminatorQidField.setToolTipText("<html>Subclass discriminator value "
                + "(e.g. Q5 human, Q11424 film). The subclass = the inherited "
                + "membership <b>AND</b> ?value wdt:&lt;prop&gt; wd:&lt;this&gt;. "
                + "Blank for a non-discriminated class.</html>");
        WikidataLinks.linkify(discriminatorLabel,
                () -> RuleNode.cleanQid(discriminatorQidField.getText()));
        JPanel discRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        discRow.add(discriminatorPidField);
        discRow.add(new JLabel("="));
        discRow.add(discriminatorQidField);
        discRow.add(discriminatorLabel);
        GridBagUtils.labeledRow(form, c, y++, "Subtype:", discRow);

        statementSourceField.setToolTipText("<html>Make this a <b>statement "
                + "reification</b>: instances are the statements of the "
                + "<b>Relation property</b> below on each member of the named class "
                + "(e.g. <i>Nomination</i> = the P1411 statements of "
                + "<i>Oscarnominations</i>). Fields draw from the statement value "
                + "and its qualifiers (\"Qualifier of\" per field). Blank = normal "
                + "class.</html>");
        GridBagUtils.labeledRow(form, c, y++, "Reifies statements of:", statementSourceField);

        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typeRow.add(typeQidField);
        typeRow.add(typeLabel);
        GridBagUtils.labeledRow(form, c, y++, typeRowLabel, typeRow);

        additionalTypesField.setToolTipText("<html>Extra type QIDs (space-separated) "
                + "for membership: an item counts if it is instance-of the type "
                + "above OR any of these.<br>e.g. add Q4193029 (zodiacal "
                + "constellation) so Aries &amp; Cancer — typed only as the "
                + "subclass — are included. Avoids a slow/over-broad P279* path.</html>");
        JPanel addTypesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        addTypesRow.add(additionalTypesField);
        addTypesRow.add(discoverTypesButton);
        fromPartsButton.setToolTipText("<html>Fill the membership from a parent "
                + "entity's parts: e.g. Academy Awards (Q19020) <b>P527</b> (has "
                + "part) → its award categories. Data-driven instead of a pasted "
                + "QID list.</html>");
        addTypesRow.add(fromPartsButton);
        GridBagUtils.labeledRow(form, c, y++, "Also include types:", addTypesRow);

        excludeTypesField.setToolTipText("<html>Type QIDs (space-separated) to "
                + "EXCLUDE: drop any item that is instance-of (P31) one of these, "
                + "even if it matched membership.<br>e.g. add Q11688446 (Roman "
                + "deity) to keep a Greek-character class free of Roman ones. "
                + "Emitted as FILTER NOT EXISTS.</html>");
        GridBagUtils.labeledRow(form, c, y++, "Exclude types:", excludeTypesField);

        relationPidField.setToolTipText("<html>Membership relation property. "
                + "<b>P31</b> = instance of (the type above); but any property "
                + "works, e.g. <b>P166</b> = award received (\"won this award\"), "
                + "<b>P39</b> = position held. Emitted as "
                + "<code>?item wdt:&lt;PID&gt; wd:&lt;type/value&gt;</code>.</html>");
        JPanel relRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        relRow.add(relationPidField);
        findRelationButton.setToolTipText(
                "Search Wikidata properties by name (e.g. \"nominated\" → P1411)");
        findRelationButton.addActionListener(e -> pickProperty());
        relRow.add(findRelationButton);
        relRow.add(relationLabel);
        GridBagUtils.labeledRow(form, c, y++, "Relation property:", relRow);
        // Keep the source-QID row label + the relation label in sync with the
        // chosen relation; clear the resolved label when the PID is hand-edited.
        relationPidField.getDocument().addDocumentListener(
                (SimpleDocumentListener) e -> {
                    updateTypeRowLabel();
                    relationLabel.setText(" ");
                });
        updateTypeRowLabel();
        // Both the type and relation labels link to their Wikidata page.
        WikidataLinks.linkify(typeLabel, () -> RuleNode.cleanQid(typeQidField.getText()));
        WikidataLinks.linkify(relationLabel, () -> RuleNode.cleanPid(relationPidField.getText()));

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        options.add(new JLabel("Limit:"));
        options.add(limitSpinner);
        options.add(requireLabelBox);
        options.add(new JLabel("lang:"));
        options.add(langField);
        GridBagUtils.wideRow(form, y++, options);

        notableOnlyBox.setToolTipText("<html>Require an English Wikipedia article "
                + "(a sitelink). A selective entry that bounds a huge class "
                + "(e.g. star Q523, ~3M) to its ~2886 NOTABLE members, so the "
                + "query completes and returns famous entities instead of timing "
                + "out. Combine with a magnitude filter + sort for the brightest "
                + "famous ones.</html>");
        GridBagUtils.wideRow(form, y++, notableOnlyBox);

        rankByBox.setToolTipText("<html>Keep the top <b>Limit</b> instances by "
                + "this measure (importance):<br><b>Notability (sitelinks)</b> — "
                + "Wikipedia language count, a generic importance proxy.<br>Or a "
                + "<b>number/date field</b> of this class (e.g. brightness, area, "
                + "population). <b>(none)</b> = arbitrary, ordered by name.</html>");
        JPanel rankRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rankRow.add(rankByBox);
        rankRow.add(rankDescBox);
        GridBagUtils.labeledRow(form, c, y++, "Rank by:", rankRow);

        seedQidsArea.setLineWrap(true);
        seedQidsArea.setWrapStyleWord(true);
        seedQidsArea.setToolTipText("<html>Explicit instance QIDs (space/comma "
                + "separated). These entities ARE the class's instances — a "
                + "curated set. Leave the Wikidata type empty to use ONLY these "
                + "(e.g. the 12 Olympians); or set a type too, to restrict it. "
                + "The WikiProject tab's \"Add selected\" fills this.</html>");
        JScrollPane seedScroll = new JScrollPane(seedQidsArea);
        seedScroll.setPreferredSize(new Dimension(360, 56));
        GridBagUtils.labeledRow(form, c, y++, "Seed QIDs:", seedScroll);

        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        searchRow.add(new JLabel("Search:"));
        searchRow.add(searchTextField);
        searchRow.add(new JLabel("Method:"));
        searchRow.add(searchModeBox);
        searchRow.add(searchTypeButton);
        GridBagUtils.wideRow(form, y++, searchRow);

        installSearchTableBehavior();

        JScrollPane tableScroll = new JScrollPane(searchTable);
        tableScroll.setPreferredSize(new Dimension(700, 180));
        GridBagUtils.wideRow(form, y++, tableScroll);

        JPanel useRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        useSelectedButton.setEnabled(false);
        useRow.add(useSelectedButton);
        GridBagUtils.wideRow(form, y++, useRow);

        // --- Identity & label section ---
        JLabel canonHeader = new JLabel("Identity & label");
        canonHeader.setFont(canonHeader.getFont().deriveFont(Font.BOLD, 13f));
        GridBagUtils.wideRow(form, y++, canonHeader);

        // No "Kind:" control. Which identity regime a class is in follows from how it
        // is built, so offering it separately only created a second answer that could
        // disagree with the first. The label below states the regime instead.
        canonicalKindLabel.setToolTipText("<html>Identity follows the class's "
                + "construction.<br><b>Source</b>: the datasource's id, and its label as "
                + "the display name.<br><b>Statement</b> and <b>Owned</b>: a key over "
                + "fields, or the owner and the site that produced it — with a display "
                + "name from a field or a template.</html>");
        GridBagUtils.labeledRow(form, c, y++, "Identity:", canonicalKindLabel);
        canonicalSourcesLabel.setToolTipText("Datasource operations supplying the source "
                + "class's identity, display label and alternate names.");
        GridBagUtils.labeledRow(form, c, y++, "Source bindings:", canonicalSourcesLabel);

        displayNameModeBox.setToolTipText("How to make the display name: the source "
                + "label, a single field's value, or a template.");
        GridBagUtils.labeledRow(form, c, y++, "Display name:", displayNameModeBox);

        displayNameFieldBox.setToolTipText("Single-valued field to show as the "
                + "label (a reference shows its own name).");
        GridBagUtils.labeledRow(form, c, y++, "  from field:", displayNameFieldBox);

        displayNameTemplateField.setToolTipText("e.g. {nominee} · {category} {year} "
                + "— {field} is replaced by that field's label.");
        GridBagUtils.labeledRow(form, c, y++, "  template:", displayNameTemplateField);

        keyFieldsField.setToolTipText("Identity key fields (space-separated) — the "
                + "grain that makes one instance unique. Prefilled from the class.");
        GridBagUtils.labeledRow(form, c, y++, "Identity key fields:", keyFieldsField);

        canonicalHint.setForeground(new Color(0xB00020));
        GridBagUtils.wideRow(form, y++, canonicalHint);

        displayNameModeBox.addActionListener(e -> updateCanonicalEnablement());

        GridBagUtils.wideRow(form, y++, summaryLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(applyButton);
        GridBagUtils.wideRow(form, y++, buttons);

        add(scroll, BorderLayout.CENTER);

        applyButton.addActionListener(e -> apply());
        searchTextField.addActionListener(e -> searchTypeButton.doClick());
        useSelectedButton.addActionListener(e -> useSelectedSearchRow());

        searchTable.getSelectionModel().addListSelectionListener(e ->
                                                                         useSelectedButton.setEnabled(searchTable.getSelectedRow() >= 0));

        updateSearchButtonState();
    }

    private void wireQuery() {
        queryRunner.wireButton(
                searchTypeButton,
                this::acceptSearchResult,
                this::buildSearchQuery,
                ex -> JOptionPane.showMessageDialog(
                        this,
                        "Search failed:\n" + ex.getMessage(),
                        "Search failed",
                        JOptionPane.ERROR_MESSAGE));

        queryRunner.wireButton(
                discoverTypesButton,
                this::acceptSubtypeResult,
                this::buildSubtypeQuery,
                ex -> JOptionPane.showMessageDialog(
                        this,
                        "Discover subtypes failed:\n" + ex.getMessage(),
                        "Discover failed",
                        JOptionPane.ERROR_MESSAGE));

        queryRunner.wireButton(
                fromPartsButton,
                this::acceptPartsResult,
                this::buildPartsQuery,
                ex -> JOptionPane.showMessageDialog(
                        this,
                        "Discover from parts failed:\n" + ex.getMessage(),
                        "Discover failed",
                        JOptionPane.ERROR_MESSAGE));
    }

    // The parent + parts pid the user last entered, for the result-dialog title.
    private String partsParentQid = "";
    private String partsParentLabel = "";

    private wikidata.explore.query.logical.RelationMembersQuery buildPartsQuery() {
        if (clazz == null) {
            return null;
        }
        JTextField parentField = new JTextField(
                RuleNode.cleanQid(typeQidField.getText()), 12);
        JTextField pidField = new JTextField("P527", 6);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Parent entity QID:"));
        form.add(parentField);
        form.add(new JLabel("Parts property (P527 = has part):"));
        form.add(pidField);
        int ok = JOptionPane.showConfirmDialog(this, form,
                "Discover membership from parent's parts",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return null;
        }
        String parent = RuleNode.cleanQid(parentField.getText());
        String pid = RuleNode.cleanPid(pidField.getText());
        if (!WikidataIds.isQid(parent) || !WikidataIds.isPid(pid)) {
            JOptionPane.showMessageDialog(this,
                    "Enter a parent QID (e.g. Q19020) and a parts property "
                            + "(e.g. P527).",
                    "Discover from parts", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        partsParentQid = parent;
        partsParentLabel = parent;
        log.accept("Discover membership: " + parent + " " + pid + " → members\n");
        return new wikidata.explore.query.logical.RelationMembersQuery(
                parent, pid, false, 1000);
    }

    private void acceptPartsResult(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        SwingUtilities.invokeLater(() -> showPartsDialog(rows));
    }

    // RelationMembersQuery rows are [QID, Label]; "Add all" / "Add selected"
    // append to the multi-QID membership field — the data-driven category list.
    private void showPartsDialog(List<List<Object>> rows) {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No members found for " + partsParentQid + ".",
                    "Discover from parts", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String[] cols = {"Label", "QID"};
        Object[][] data = new Object[rows.size()][2];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            data[i][0] = r.size() > 1 ? r.get(1) : "";
            data[i][1] = r.size() > 0 ? r.get(0) : "";
        }
        DefaultTableModel model = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r, int col) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        WikidataLinks.installOnColumn(table, 1);

        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(560, 320));

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Parts of " + partsParentQid + " (" + rows.size() + ")",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(0, 6));
        dialog.add(new JLabel("  Add these as membership targets "
                + "(\"Also include types\")."), BorderLayout.NORTH);
        dialog.add(sp, BorderLayout.CENTER);

        JButton addSelected = new JButton("Add selected");
        addSelected.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e ->
                addSelected.setEnabled(table.getSelectedRow() >= 0));
        JButton addAll = new JButton("Add all " + rows.size());
        JButton close = new JButton("Close");
        addSelected.addActionListener(ev -> {
            for (int viewRow : table.getSelectedRows()) {
                addAdditionalType(String.valueOf(table.getValueAt(viewRow, 1)));
            }
        });
        addAll.addActionListener(ev -> {
            for (int i = 0; i < table.getRowCount(); i++) {
                addAdditionalType(String.valueOf(table.getValueAt(i, 1)));
            }
            dialog.dispose();
        });
        close.addActionListener(ev -> dialog.dispose());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        south.add(addSelected);
        south.add(addAll);
        south.add(close);
        dialog.add(south, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private DiscoverSubtypesQuery buildSubtypeQuery() {
        if (clazz == null) {
            return null;
        }
        String base = clazz.instanceMapping().sourceQid();
        if (base == null || base.isBlank()) {
            JOptionPane.showMessageDialog(this,
                    "Set the Wikidata type first, then discover its subtypes.",
                    "No type selected", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        log.accept("Discover subtypes of " + base + "\n");
        return new DiscoverSubtypesQuery(base, 40);
    }

    private void acceptSubtypeResult(TableQueryResult result) {
        List<List<Object>> rows =
                result == null ? List.of() : result.rows();
        SwingUtilities.invokeLater(() -> showSubtypeDialog(rows));
    }

    // Each row: how many NEW members the subtype adds + examples; clicking
    // "Add" appends its QID to the multi-QID membership field.
    private void showSubtypeDialog(List<List<Object>> rows) {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No subtypes found for this type.",
                    "Discover subtypes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String[] cols = {"Subtype", "Instances", "Examples", "QID"};
        Object[][] data = new Object[rows.size()][4];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            for (int j = 0; j < 4; j++) {
                data[i][j] = j < r.size() ? r.get(j) : "";
            }
        }

        DefaultTableModel model = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r, int col) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(50);
        table.getColumnModel().getColumn(2).setPreferredWidth(360);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        WikidataLinks.installOnColumn(table, 3); // QID column → clickable link

        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(720, 300));

        JButton addButton = new JButton("Add selected to membership");
        addButton.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e ->
                addButton.setEnabled(table.getSelectedRow() >= 0));

        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(this),
                "Subtypes of " + clazz.instanceMapping().displaySource(),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(0, 6));
        dialog.add(new JLabel(
                "  \"Instances\" = how many entities the subtype has (≈ how many it "
                        + "adds). Add the relevant subtypes."),
                BorderLayout.NORTH);
        dialog.add(sp, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton closeButton = new JButton("Close");
        south.add(addButton);
        south.add(closeButton);
        dialog.add(south, BorderLayout.SOUTH);

        addButton.addActionListener(ev -> {
            // Add every selected row, not just the first.
            for (int viewRow : table.getSelectedRows()) {
                addAdditionalType(String.valueOf(table.getValueAt(viewRow, 3)));
            }
        });
        closeButton.addActionListener(ev -> dialog.dispose());

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void addAdditionalType(String rawQid) {
        String qid = RuleNode.cleanQid(rawQid);
        if (!WikidataIds.isQid(qid)) {
            return;
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String tok : additionalTypesField.getText().trim().split("[,;\\s]+")) {
            String q = RuleNode.cleanQid(tok);
            if (WikidataIds.isQid(q)) set.add(q);
        }
        set.add(qid);
        additionalTypesField.setText(String.join(" ", set));
        apply();
        log.accept("Added membership type " + qid + "\n");
    }

    private ClassSearchQuery buildSearchQuery() {
        if (clazz == null) {
            return null;
        }

        String text = searchText();

        if (text.isBlank()) {
            return null;
        }

        ClassSearchQuery.Mode mode =
                (ClassSearchQuery.Mode) searchModeBox.getSelectedItem();

        log.accept("Class/type search: "
                           + text
                           + " using "
                           + mode
                           + "\n");

        searchModel.setRows(List.of());
        useSelectedButton.setEnabled(false);

        return new ClassSearchQuery(
                text,
                mode,
                25);
    }

    private String searchText() {
        String text =
                searchTextField.getText() == null
                        ? ""
                        : searchTextField.getText().trim();

        if (text.isBlank()) {
            text =
                    classNameField.getText() == null
                            ? ""
                            : classNameField.getText().trim();
        }

        return text;
    }

    private void acceptSearchResult(TableQueryResult result) {
        List<SearchResult> rows = new ArrayList<>();

        if (result != null) {
            for (List<Object> row : result.rows()) {
                rows.add(new SearchResult(
                        value(row, 0),
                        value(row, 1),
                        value(row, 2)));
            }
        }

        SwingUtilities.invokeLater(() -> {
            searchModel.setRows(rows);
            useSelectedButton.setEnabled(false);
        });
    }

    private static String value(List<Object> row, int index) {
        if (row == null || index >= row.size()) {
            return "";
        }

        Object v = row.get(index);
        return v == null ? "" : String.valueOf(v);
    }

    private void updateSearchButtonState() {
        boolean ready = clazz != null && queryRunner != null;
        searchTypeButton.setEnabled(ready);
        discoverTypesButton.setEnabled(ready);
        fromPartsButton.setEnabled(ready);
    }

    private void useSelectedSearchRow() {
        int row = searchTable.getSelectedRow();

        if (row < 0) {
            return;
        }

        SearchResult r =
                searchModel.row(searchTable.convertRowIndexToModel(row));

        useSourceQid(r.qid(), r.label());
    }

    private void installSearchTableBehavior() {
        searchTable.setRowHeight(24);
        searchTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        searchTable.getColumnModel().getColumn(0)
                   .setPreferredWidth(80);
        searchTable.getColumnModel().getColumn(1)
                   .setPreferredWidth(180);
        searchTable.getColumnModel().getColumn(2)
                   .setPreferredWidth(420);

        WikidataLinks.installOnColumn(searchTable, 0); // QID column → clickable link
    }

    private void apply() {
        if (clazz == null) {
            return;
        }

        boolean wasStatementClass = clazz.reifiesStatements();

        clazz.className(classNameField.getText());
        clazz.alias(aliasField.getText());

        Object base = baseClassBox.getSelectedItem();
        clazz.baseClassName(base == null || NO_BASE.equals(base) ? "" : base.toString());
        clazz.discriminatorPid(RuleNode.cleanPid(discriminatorPidField.getText()));
        clazz.discriminatorQid(RuleNode.cleanQid(discriminatorQidField.getText()));
        FieldSourceMapping m = clazz.instanceMapping();
        m.sourceQid(typeQidField.getText());
        m.additionalTypeQids().clear();
        for (String tok : additionalTypesField.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(tok);
            if (WikidataIds.isQid(qid)) m.additionalTypeQids().add(qid);
        }
        m.excludedTypeQids().clear();
        for (String tok : excludeTypesField.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(tok);
            if (WikidataIds.isQid(qid)) m.excludedTypeQids().add(qid);
        }
        String relPid = RuleNode.cleanPid(relationPidField.getText());
        if (!WikidataIds.isPid(relPid)) {
            relPid = "P31";
        }
        m.propertyPid(relPid);
        String statementSourceClass = statementSourceField.getText().trim();
        // This editor owns exactly one statement declaration: the source class. A
        // blank one does NOT mean "not a statement class" — every shipped statement
        // class discovers its subjects from the property and has no source class at
        // all — so the source is dropped only when no property keeps it alive
        // either. Copying carries the declarations this editor cannot see, by
        // construction rather than by a list maintained here; the Statement panel
        // remains their single editor.
        StatementClassSource previousStatementSource = clazz.statementSource();
        boolean staysAStatementClass = !statementSourceClass.isBlank()
                || (previousStatementSource != null
                        && previousStatementSource.hasProperty());
        if (!staysAStatementClass) {
            clazz.statementSource(null);
        } else if (previousStatementSource == null) {
            clazz.statementSource(
                    new StatementClassSource(statementSourceClass, relPid));
        } else {
            StatementClassSource nextStatementSource = previousStatementSource.copy();
            nextStatementSource.sourceClassName(statementSourceClass);
            clazz.statementSource(nextStatementSource);
        }
        // Preserve the resolved relation label (from "Find…" or a prior load) so
        // it persists and renders; default P31 to "instance of".
        String relLabelText = relationLabel.getText().trim();
        m.propertyLabel(relPid.equals("P31") ? "instance of"
                : (relLabelText.isEmpty() ? "" : relLabelText));
        m.direction(RuleDirection.ITEM_TO_ROOT);
        // Commit a value typed into the spinner editor but not yet entered, so
        // Apply reads what's on screen (an out-of-range value otherwise reverts).
        try {
            limitSpinner.commitEdit();
        } catch (java.text.ParseException ignore) {
        }
        m.limit(((Number) limitSpinner.getValue()).intValue());
        m.requireLabel(requireLabelBox.isSelected());
        m.requireSitelink(notableOnlyBox.isSelected());
        m.labelLanguage(langField.getText());

        Object rank = rankByBox.getSelectedItem();
        if (rank == null || NO_RANK.equals(rank)) {
            m.rankBy("");
        } else if (RANK_NOTABILITY.equals(rank)) {
            m.rankBy(FieldSourceMapping.RANK_BY_SITELINKS);
        } else {
            m.rankBy(rank.toString());
        }
        m.rankDescending(rankDescBox.isSelected());

        clazz.seedQids().clear();
        for (String tok : seedQidsArea.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(tok);
            if (WikidataIds.isQid(qid) && !clazz.seedQids().contains(qid)) {
                clazz.seedQids().add(qid);
            }
        }

        titleLabel.setText("Class: " + clazz.className());
        typeLabel.setText(m.displaySource());

        // Multi-target/-type membership → auto-add the intrinsic grouping fields
        // (type, and target for a relation) as real, editable model fields.
        java.util.List<String> added =
                wikidata.explore.model.MembershipFields.ensure(clazz);
        if (!added.isEmpty()) {
            log.accept("Added membership fields: " + String.join(", ", added)
                    + " (edit/remove in the class)\n");
        }

        applyCanonical();

        // This older class-source entry point can also create a statement class.
        // applyCanonical() above rebuilds CanonicalSpec from this panel's controls,
        // so defaults MUST be materialized afterwards; doing it before that call
        // merely wrote values which were immediately discarded. Preserve anything
        // explicitly entered in the same apply: fill only a missing key and the
        // entity-only LABEL display, while always making the identity kind agree
        // with the newly assigned statement source.
        if (!wasStatementClass && clazz.reifiesStatements()) {
            CanonicalSpec canonical = clazz.canonical();
            if (canonical.keyFields().isEmpty()) {
                wikidata.explore.model.StatementCanonicalDefaults
                        .replaceKeyWithSuggestion(clazz);
            }
            if (canonical.displayNameMode() == CanonicalSpec.DisplayNameMode.LABEL) {
                wikidata.explore.model.StatementCanonicalDefaults
                        .replaceDisplayWithSuggestion(clazz);
            }
        }

        updateSummary();
        afterChange.accept(null);
    }

    // --- Identity & label (canonicalization) ---

    /** Loads the class's canonical spec into the section. */
    private void loadCanonical() {
        populateDisplayNameFields();

        CanonicalSpec spec = clazz == null
                ? new CanonicalSpec()
                : clazz.canonical();

        displayNameModeBox.setSelectedItem(switch (spec.displayNameMode()) {
            case FIELD -> DN_FIELD;
            case TEMPLATE -> DN_TEMPLATE;
            case LABEL -> DN_LABEL;
        });
        displayNameFieldBox.setSelectedItem(spec.displayNameField());
        displayNameTemplateField.setText(spec.displayNameTemplate());
        keyFieldsField.setText(String.join(" ", spec.keyFields()));

        updateCanonicalEnablement();
    }

    // Only SINGLE-cardinality, non-identity fields can be a Field-mode display name.
    private void populateDisplayNameFields() {
        displayNameFieldBox.removeAllItems();
        if (clazz == null) {
            return;
        }
        for (GeneratedFieldModel f : clazz.fields()) {
            if (f != null && !f.isNameField()
                    && f.cardinality() != FieldCardinality.COLLECTION) {
                displayNameFieldBox.addItem(f.name());
            }
        }
    }

    private void updateCanonicalEnablement() {
        boolean hasClass = clazz != null;
        boolean keyed = hasClass
                && CanonicalEditorPolicy.editsCanonicalKey(clazz.classKind());
        canonicalKindLabel.setText(clazz == null ? ""
                : switch (clazz.classKind()) {
                    case SOURCE -> KIND_ENTITY;
                    case STATEMENT -> KIND_DERIVED;
                    case OWNED -> "Owned (owner + production site)";
                });
        canonicalSourcesLabel.setText(describeClassSources());

        // Display policy is independent of identity: even a source-identified class
        // may deliberately compose a name from its configured fields.
        displayNameModeBox.setEnabled(hasClass);

        String mode = (String) displayNameModeBox.getSelectedItem();
        displayNameFieldBox.setEnabled(hasClass && DN_FIELD.equals(mode));
        displayNameTemplateField.setEnabled(hasClass && DN_TEMPLATE.equals(mode));
        keyFieldsField.setEnabled(keyed);

        canonicalHint.setText(canonicalWarning(clazz == null ? null : clazz.classKind(), mode));
    }

    private String describeClassSources() {
        if (clazz == null || clazz.classKind() != ClassKind.SOURCE) return "—";
        ClassSourceBindings.synchronize(clazz);
        datasource.api.DatasourceRegistry registry = datasource.Datasources.standard();
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (SourceBindingSlot slot : java.util.List.of(SourceBindingSlot.CLASS_IDENTITY,
                SourceBindingSlot.CLASS_LABEL, SourceBindingSlot.CLASS_ALIASES)) {
            datasource.api.SourceBinding binding = ClassSourceBindings.binding(clazz, slot);
            if (binding == null) continue;
            datasource.api.DatasourceOperation operation = binding.resolve(registry);
            labels.add(operation.displayName());
        }
        return labels.isEmpty() ? "—" : String.join(" · ", labels);
    }

    // A composed display name must resolve; only a source class has a source label.
    private String canonicalWarning(ClassKind kind, String mode) {
        if (kind == null) {
            return " ";
        }
        if (DN_FIELD.equals(mode) && displayNameFieldBox.getItemCount() == 0) {
            return "No single-valued field to use as the display name — add one or use a template.";
        }
        if (DN_TEMPLATE.equals(mode) && displayNameTemplateField.getText().isBlank()) {
            return "Template is empty — the display name won't resolve.";
        }
        if (DN_LABEL.equals(mode) && !CanonicalEditorPolicy.hasSourceLabel(kind)) {
            return "This class has no source label — pick Field or Template.";
        }
        return " ";
    }

    private void applyCanonical() {
        if (clazz == null) {
            return;
        }
        // How a class is BUILT decides its identity regime, so the editor no longer
        // offers it as a separate choice that could disagree with the class's kind.
        String mode = (String) displayNameModeBox.getSelectedItem();

        Object selectedField = displayNameFieldBox.getSelectedItem();
        CanonicalSpec.DisplayNameMode displayMode = DN_TEMPLATE.equals(mode)
                ? CanonicalSpec.DisplayNameMode.TEMPLATE
                : DN_FIELD.equals(mode) ? CanonicalSpec.DisplayNameMode.FIELD
                : CanonicalSpec.DisplayNameMode.LABEL;
        CanonicalSpec spec = CanonicalEditorPolicy.spec(
                clazz.classKind(), displayMode,
                selectedField == null ? "" : selectedField.toString(),
                displayNameTemplateField.getText(), langField.getText(),
                keyFieldsField.getText());

        clazz.canonical(spec);

        String warning = canonicalWarning(clazz.classKind(), mode);
        if (warning != null && !warning.isBlank()) {
            log.accept("Identity & label: " + warning + "\n");
        }
    }

    private void updateSummary() {
        if (clazz == null) {
            summaryLabel.setText(" ");
            return;
        }

        summaryLabel.setText(
                "Current source: items with instance of "
                        + clazz.instanceMapping().displaySource());
    }

    private void clear() {
        titleLabel.setText("Class");
        classNameField.setText("");
        aliasField.setText("");
        searchTextField.setText("");
        typeQidField.setText("");
        typeLabel.setText("(not selected)");
        relationPidField.setText("P31");
        summaryLabel.setText(" ");
        searchModel.setRows(List.of());
        updateSearchButtonState();
    }

    private record SearchResult(
            String qid,
            String label,
            String description) {
    }

    private static final class SearchTableModel extends AbstractTableModel {

        private final String[] columns =
                {"QID", "Label", "Description"};

        private List<SearchResult> rows = new ArrayList<>();

        public void setRows(List<SearchResult> rows) {
            this.rows = rows == null ? new ArrayList<>() : rows;
            fireTableDataChanged();
        }

        public SearchResult row(int i) {
            return rows.get(i);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SearchResult r = rows.get(rowIndex);

            return switch (columnIndex) {
                case 0 -> r.qid();
                case 1 -> r.label();
                case 2 -> r.description();
                default -> "";
            };
        }
    }

    // The source-QID field is a "type/class" only with the default P31 relation.
    // For any other relation it holds the relation's TARGET (e.g. P166 → the award
    // won), so relabel it to match — this is exactly the confusion P1411 caused.
    private void updateTypeRowLabel() {
        String pid = RuleNode.cleanPid(relationPidField.getText());
        if (pid.isBlank() || pid.equals("P31")) {
            typeRowLabel.setText("Wikidata type/class:");
        } else {
            typeRowLabel.setText("Relation target (" + pid + "):");
        }
    }

    private WikidataApiClient api() {
        if (api == null) {
            api = new WikidataApiClient("quiz-modelbuilder/1.0 (ggyepesi@gmail.com)");
            // Surface every API request (URL + timing) on stdout AND in the query
            // log, the same way SPARQL is logged.
            api.log(s -> {
                System.out.print(s);
                log.accept(s);
            });
        }
        return api;
    }

    // Modal "find a property by name" dialog: type a word, see matching Wikidata
    // properties (PID label — description), pick one to fill the relation PID.
    // A real JDialog (not JOptionPane) so Enter = search, not "OK & close".
    private void pickProperty() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Find relation property", Dialog.ModalityType.APPLICATION_MODAL);

        // Seed the search from the PID already in the relation field — a PID is an
        // exact match (just that property), which is clearer than seeding the
        // label (a fuzzy text match that also pulls in related properties). Fall
        // back to the resolved label only when there's no PID yet.
        String seed = relationPidField.getText().trim();
        if (seed.isEmpty()) {
            seed = relationLabel.getText().trim();
        }
        JTextField input = new JTextField(seed, 20);
        JButton searchBtn = new JButton("Search");
        JButton useBtn = new JButton("Use");
        JButton cancelBtn = new JButton("Cancel");
        JLabel status = new JLabel(" ");
        DefaultListModel<WikidataApiClient.SearchResult> model = new DefaultListModel<>();
        JList<WikidataApiClient.SearchResult> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, foc);
                if (v instanceof WikidataApiClient.SearchResult r) {
                    setText("<html><b>" + r.qid() + "</b> " + esc(r.label())
                            + (r.description() == null || r.description().isBlank()
                                ? "" : " — <i>" + esc(r.description()) + "</i>") + "</html>");
                }
                return this;
            }
        });

        // Latest-wins: only the most recent search updates the list, so a slow/
        // timed-out earlier search (e.g. the auto-search on open) can't overwrite
        // or contradict a later successful one.
        int[] gen = {0};
        Runnable doSearch = () -> {
            String q = input.getText().trim();
            if (q.isEmpty()) return;
            final int myGen = ++gen[0];
            searchBtn.setEnabled(false);
            status.setText("Searching…");
            model.clear();
            new SwingWorker<List<WikidataApiClient.SearchResult>, Void>() {
                @Override protected List<WikidataApiClient.SearchResult> doInBackground()
                        throws Exception {
                    try {
                        return api().searchEntities(q, 20, "property");
                    } catch (java.io.IOException firstTry) {
                        // The first HTTPS call after start can hit a cold-connection
                        // timeout — retry once before giving up.
                        return api().searchEntities(q, 20, "property");
                    }
                }
                @Override protected void done() {
                    if (myGen != gen[0]) {
                        return; // a newer search superseded this one
                    }
                    searchBtn.setEnabled(true);
                    try {
                        for (WikidataApiClient.SearchResult r : get()) model.addElement(r);
                        status.setText(model.isEmpty()
                                ? "No properties match \"" + q + "\"."
                                : model.size() + " match(es) — pick one.");
                        if (!model.isEmpty()) list.setSelectedIndex(0);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        status.setText("Search failed: " + cause.getMessage());
                    }
                }
            }.execute();
        };
        Runnable use = () -> {
            WikidataApiClient.SearchResult picked = list.getSelectedValue();
            if (picked != null) {
                relationPidField.setText(picked.qid());   // fires listener (clears label)
                updateTypeRowLabel();
                relationLabel.setText(picked.label());     // then set the picked label
                dialog.dispose();
            }
        };

        searchBtn.addActionListener(e -> doSearch.run());
        input.addActionListener(e -> doSearch.run());
        useBtn.addActionListener(e -> use.run());
        cancelBtn.addActionListener(e -> dialog.dispose());
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) use.run();
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        top.add(new JLabel("Search:"));
        top.add(input);
        top.add(searchBtn);
        JPanel south = new JPanel(new BorderLayout());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        btns.add(useBtn);
        btns.add(cancelBtn);
        south.add(status, BorderLayout.WEST);
        south.add(btns, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(top, BorderLayout.NORTH);
        JScrollPane sc = new JScrollPane(list);
        sc.setPreferredSize(new Dimension(460, 240));
        panel.add(sc, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        if (!seed.isEmpty()) {
            doSearch.run();
        }
        SwingUtilities.invokeLater(input::requestFocusInWindow);
        dialog.setVisible(true);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // DocumentListener whose three callbacks all run the same action.
    @FunctionalInterface
    private interface SimpleDocumentListener extends javax.swing.event.DocumentListener {
        void changed(javax.swing.event.DocumentEvent e);
        @Override default void insertUpdate(javax.swing.event.DocumentEvent e) { changed(e); }
        @Override default void removeUpdate(javax.swing.event.DocumentEvent e) { changed(e); }
        @Override default void changedUpdate(javax.swing.event.DocumentEvent e) { changed(e); }
    }

}
