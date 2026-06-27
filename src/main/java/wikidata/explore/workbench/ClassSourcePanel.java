package wikidata.explore.workbench;

import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.query.logical.ClassSearchQuery;
import wikidata.explore.query.logical.DiscoverSubtypesQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.api.WikidataApiClient;

import java.net.URI;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ClassSourcePanel extends JPanel {

    private final JComboBox<ClassSearchQuery.Mode> searchModeBox =
            new JComboBox<>(ClassSearchQuery.Mode.values());

    private GeneratedClassModel clazz;
    private SwingQueryRunner queryRunner;

    private Consumer<String> log = s -> {};
    private Consumer<Void> afterChange = v -> {};

    private final JLabel titleLabel = new JLabel("Class");

    private final JTextField classNameField = new JTextField(18);

    // "Extends" base class: this class inherits the base's fields/membership
    // and adds its own (see GeneratedClassModel.effectiveFields). Blank = none.
    private static final String NO_BASE = "(none)";
    private final JComboBox<String> baseClassBox = new JComboBox<>();
    private Supplier<List<String>> baseClassCandidates = List::of;

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
            new JSpinner(new SpinnerNumberModel(200, 1, 10000, 10));

    private final JCheckBox requireLabelBox =
            new JCheckBox("Require label", true);

    private final JTextField langField =
            new JTextField("en", 4);

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
        addWide(form, c, y++, titleLabel);

        JLabel question =
                new JLabel("How do we find instances of this class?");
        question.setFont(question.getFont().deriveFont(Font.ITALIC));
        addWide(form, c, y++, question);

        addRow(form, c, y++, "Class name:", classNameField);

        baseClassBox.setToolTipText("<html>Extend another class: this class "
                + "inherits the base's fields and membership and adds its own "
                + "(a subclass field with the same name overrides). Lets a shared "
                + "base (e.g. Person) be reused and extended per domain.</html>");
        addRow(form, c, y++, "Extends:", baseClassBox);

        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typeRow.add(typeQidField);
        typeRow.add(typeLabel);
        addRow(form, c, y++, typeRowLabel, typeRow);

        additionalTypesField.setToolTipText("<html>Extra type QIDs (space-separated) "
                + "for membership: an item counts if it is instance-of the type "
                + "above OR any of these.<br>e.g. add Q4193029 (zodiacal "
                + "constellation) so Aries &amp; Cancer — typed only as the "
                + "subclass — are included. Avoids a slow/over-broad P279* path.</html>");
        JPanel addTypesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        addTypesRow.add(additionalTypesField);
        addTypesRow.add(discoverTypesButton);
        addRow(form, c, y++, "Also include types:", addTypesRow);

        excludeTypesField.setToolTipText("<html>Type QIDs (space-separated) to "
                + "EXCLUDE: drop any item that is instance-of (P31) one of these, "
                + "even if it matched membership.<br>e.g. add Q11688446 (Roman "
                + "deity) to keep a Greek-character class free of Roman ones. "
                + "Emitted as FILTER NOT EXISTS.</html>");
        addRow(form, c, y++, "Exclude types:", excludeTypesField);

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
        addRow(form, c, y++, "Relation property:", relRow);
        // Keep the source-QID row label + the relation label in sync with the
        // chosen relation; clear the resolved label when the PID is hand-edited.
        relationPidField.getDocument().addDocumentListener(
                (SimpleDocumentListener) e -> {
                    updateTypeRowLabel();
                    relationLabel.setText(" ");
                });
        updateTypeRowLabel();
        // Both the type and relation labels link to their Wikidata page.
        linkify(typeLabel, () -> RuleNode.cleanQid(typeQidField.getText()));
        linkify(relationLabel, () -> RuleNode.cleanPid(relationPidField.getText()));

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        options.add(new JLabel("Limit:"));
        options.add(limitSpinner);
        options.add(requireLabelBox);
        options.add(new JLabel("lang:"));
        options.add(langField);
        addWide(form, c, y++, options);

        notableOnlyBox.setToolTipText("<html>Require an English Wikipedia article "
                + "(a sitelink). A selective entry that bounds a huge class "
                + "(e.g. star Q523, ~3M) to its ~2886 NOTABLE members, so the "
                + "query completes and returns famous entities instead of timing "
                + "out. Combine with a magnitude filter + sort for the brightest "
                + "famous ones.</html>");
        addWide(form, c, y++, notableOnlyBox);

        rankByBox.setToolTipText("<html>Keep the top <b>Limit</b> instances by "
                + "this measure (importance):<br><b>Notability (sitelinks)</b> — "
                + "Wikipedia language count, a generic importance proxy.<br>Or a "
                + "<b>number/date field</b> of this class (e.g. brightness, area, "
                + "population). <b>(none)</b> = arbitrary, ordered by name.</html>");
        JPanel rankRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rankRow.add(rankByBox);
        rankRow.add(rankDescBox);
        addRow(form, c, y++, "Rank by:", rankRow);

        seedQidsArea.setLineWrap(true);
        seedQidsArea.setWrapStyleWord(true);
        seedQidsArea.setToolTipText("<html>Explicit instance QIDs (space/comma "
                + "separated). These entities ARE the class's instances — a "
                + "curated set. Leave the Wikidata type empty to use ONLY these "
                + "(e.g. the 12 Olympians); or set a type too, to restrict it. "
                + "The WikiProject tab's \"Add selected\" fills this.</html>");
        JScrollPane seedScroll = new JScrollPane(seedQidsArea);
        seedScroll.setPreferredSize(new Dimension(360, 56));
        addRow(form, c, y++, "Seed QIDs:", seedScroll);

        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        searchRow.add(new JLabel("Search:"));
        searchRow.add(searchTextField);
        searchRow.add(new JLabel("Method:"));
        searchRow.add(searchModeBox);
        searchRow.add(searchTypeButton);
        addWide(form, c, y++, searchRow);

        installSearchTableBehavior();

        JScrollPane tableScroll = new JScrollPane(searchTable);
        tableScroll.setPreferredSize(new Dimension(700, 180));
        addWide(form, c, y++, tableScroll);

        JPanel useRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        useSelectedButton.setEnabled(false);
        useRow.add(useSelectedButton);
        addWide(form, c, y++, useRow);

        addWide(form, c, y++, summaryLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(applyButton);
        addWide(form, c, y++, buttons);

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
        if (!qid.matches("Q\\d+")) {
            return;
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String tok : additionalTypesField.getText().trim().split("[,;\\s]+")) {
            String q = RuleNode.cleanQid(tok);
            if (q.matches("Q\\d+")) set.add(q);
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

        searchTable.getColumnModel().getColumn(0)
                   .setCellRenderer(new QidLinkRenderer());

        searchTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int viewRow = searchTable.rowAtPoint(e.getPoint());
                int viewCol = searchTable.columnAtPoint(e.getPoint());

                if (viewRow < 0 || viewCol != 0) {
                    return;
                }

                Object val = searchTable.getValueAt(viewRow, viewCol);
                String qid = val == null ? "" : val.toString();

                if (qid.matches("Q\\d+")) {
                    openInBrowser("https://www.wikidata.org/wiki/" + qid);
                }
            }
        });

        searchTable.addMouseMotionListener(
                new java.awt.event.MouseMotionAdapter() {
                    @Override
                    public void mouseMoved(java.awt.event.MouseEvent e) {
                        int col = searchTable.columnAtPoint(e.getPoint());

                        searchTable.setCursor(col == 0
                                                      ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                                      : Cursor.getDefaultCursor());
                    }
                });
    }

    private void apply() {
        if (clazz == null) {
            return;
        }

        clazz.className(classNameField.getText());

        Object base = baseClassBox.getSelectedItem();
        clazz.baseClassName(base == null || NO_BASE.equals(base) ? "" : base.toString());

        FieldSourceMapping m = clazz.instanceMapping();
        m.sourceQid(typeQidField.getText());
        m.additionalTypeQids().clear();
        for (String tok : additionalTypesField.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(tok);
            if (qid.matches("Q\\d+")) m.additionalTypeQids().add(qid);
        }
        m.excludedTypeQids().clear();
        for (String tok : excludeTypesField.getText().trim().split("[,;\\s]+")) {
            String qid = RuleNode.cleanQid(tok);
            if (qid.matches("Q\\d+")) m.excludedTypeQids().add(qid);
        }
        String relPid = RuleNode.cleanPid(relationPidField.getText());
        if (!relPid.matches("P\\d+")) {
            relPid = "P31";
        }
        m.propertyPid(relPid);
        // Preserve the resolved relation label (from "Find…" or a prior load) so
        // it persists and renders; default P31 to "instance of".
        String relLabelText = relationLabel.getText().trim();
        m.propertyLabel(relPid.equals("P31") ? "instance of"
                : (relLabelText.isEmpty() ? "" : relLabelText));
        m.direction(RuleDirection.ITEM_TO_ROOT);
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
            if (qid.matches("Q\\d+") && !clazz.seedQids().contains(qid)) {
                clazz.seedQids().add(qid);
            }
        }

        titleLabel.setText("Class: " + clazz.className());
        typeLabel.setText(m.displaySource());

        updateSummary();
        afterChange.accept(null);
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

    private static final class QidLinkRenderer
            extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean selected,
                boolean focus,
                int row,
                int col) {

            super.getTableCellRendererComponent(
                    table,
                    value,
                    selected,
                    focus,
                    row,
                    col);

            String text = value == null ? "" : value.toString();

            if (!selected && text.matches("Q\\d+")) {
                setForeground(new Color(0, 80, 200));
                setText("<html><u>" + text + "</u></html>");
            } else {
                setText(text);
            }

            return this;
        }
    }

    private static void openInBrowser(String url) {
        aux.BrowserLauncher.open(url);
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

        // Seed the search from the resolved label if we have one, else from
        // whatever is already in the relation field (e.g. a hand-typed "P1411"),
        // so opening Find immediately searches instead of showing a blank list.
        String seed = relationLabel.getText().trim();
        if (seed.isEmpty()) {
            seed = relationPidField.getText().trim();
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

    // Make a label act as a link to the Wikidata page of the id supplied at
    // click-time (a PID or QID); no-op when there's no valid id.
    private void linkify(JLabel label, Supplier<String> idSupplier) {
        label.setForeground(new Color(0x1a0dab));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                String id = idSupplier.get();
                if (id == null || !id.matches("[PQ]\\d+")) return;
                try {
                    Desktop.getDesktop().browse(
                            URI.create("https://www.wikidata.org/wiki/"
                                    + (id.startsWith("P") ? "Property:" : "") + id));
                } catch (Exception ex) {
                    log.accept("Could not open " + id + ": " + ex.getMessage() + "\n");
                }
            }
        });
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

    private static void addRow(
            JPanel form,
            GridBagConstraints c,
            int y,
            String label,
            JComponent comp) {
        addRow(form, c, y, new JLabel(label), comp);
    }

    private static void addRow(
            JPanel form,
            GridBagConstraints c,
            int y,
            JLabel label,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(label, c);

        c.gridx = 1;
        c.weightx = 1;
        form.add(comp, c);
    }

    private static void addWide(
            JPanel form,
            GridBagConstraints c,
            int y,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(comp, c);
        c.gridwidth = 1;
    }
}