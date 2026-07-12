package wikidata.explore.workbench;

import wikidata.explore.codegen.GeneratedQuizableSourceGenerator;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyScore;
import wikidata.explore.model.*;
import wikidata.explore.query.logical.DiscoverDBpediaPropertiesQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class FieldSourcePanel extends JPanel {

    private static final String NEW_CLASS_SENTINEL = "New class...";

    private GeneratedFieldModel field;
    private GeneratedProjectModel projectModel;
    private SwingQueryRunner queryRunner;

    private final JButton discoverDbpediaButton = new JButton("Discover properties");
    private final JButton examplesButton = new JButton("Examples…");
    private final JLabel applyStatusLabel = new JLabel(" ");

    private Consumer<Void> afterChange = v -> {};
    private Consumer<GeneratedFieldModel> afterApplyField = f -> {};
    private Runnable onSampleRequested = () -> {};

    private Map<String, WikidataProperty> propertyCache = Map.of();
    private boolean updatingObjectTypeBox = false;

    private final JLabel titleLabel = new JLabel("Field");

    private final JTextField fieldNameField = new JTextField(16);
    private final JComboBox<wikidata.explore.model.EdgeMembershipMode> edgeMembershipBox =
            new JComboBox<>(wikidata.explore.model.EdgeMembershipMode.values());
    // Advanced: constrain related values to the chosen class's membership
    // (checked = INHERIT, unchecked = NONE). Replaces the "Edge membership" combo.
    private final JCheckBox onlyRelatedOfTypeBox =
            new JCheckBox("only related items that are the chosen class", true);

    private static final String NO_SORT = "(none)";
    private final JComboBox<String> sortFieldBox = new JComboBox<>();
    private final JComboBox<String> sortDirBox =
            new JComboBox<>(new String[]{"ascending", "descending"});

    private final JComboBox<FieldType> typeBox =
            new JComboBox<>(FieldType.values());
    private final JComboBox<String> objectTypeBox = new JComboBox<>();
    private final JComboBox<FieldCardinality> shapeBox =
            new JComboBox<>(FieldCardinality.values());
    private final JComboBox<FieldRenderMode> renderModeBox =
            new JComboBox<>(FieldRenderMode.values());

    private final JCheckBox requiredBox =
            new JCheckBox("Required (membership filter — drops entities without it)");

    // Post-transform expectation (#96) for a reified/statement class's field:
    // NONE / EXPECTED (keep + report the missing) / REQUIRED (drop the missing).
    private final JComboBox<wikidata.explore.model.FieldExpectation> expectationBox =
            new JComboBox<>(wikidata.explore.model.FieldExpectation.values());

    // Optional numeric filter on a numeric property (e.g. apparentMagnitude
    // <= 3 to keep bright/notable stars). "—" = no filter.
    private static final String NO_OP = "—";
    private final JComboBox<String> filterOpBox =
            new JComboBox<>(new String[]{NO_OP, "<=", "<", ">=", ">", "=", "!="});
    private final JTextField filterValueField = new JTextField(6);

    // Which way the property points. Only consumed for related-object edges:
    // outgoing = this entity has the property (?this Pxx ?value); incoming =
    // other entities point here (?value Pxx ?this), e.g. stars whose
    // "constellation" (P59) is this constellation.
    private final JComboBox<RuleDirection> directionBox =
            new JComboBox<>(RuleDirection.values());

    // How a related value is loaded: Auto decides; "Related objects"
    // (CHILD_OBJECTS) fetches full sub-objects with their own fields (a
    // sub-class, e.g. a constellation's stars with magnitude); "Related entity
    // values" keeps just id+label references that resolve within the set.
    private final JComboBox<FieldProductionKind> productionBox =
            new JComboBox<>(FieldProductionKind.values());

    private final JTextField propertyPidField = new JTextField(10);
    // For a field of a statement-reification class: the qualifier PID this field
    // draws from (e.g. P585 → year). Blank = a direct/value field.
    private final JTextField qualifierPidField = new JTextField(6);
    private final JLabel propertyLabel = new JLabel("(not selected)");

    // COMPANION_MATCH: which of THIS record's fields form the match key — the
    // subject (the entity that carries the companion statement, e.g. nominee), the
    // value (matched to the companion's ps value), and the role (matched to the
    // companion's role qualifier). Subject blank = the reify source.
    private final JComboBox<String> subjectBox = new JComboBox<>();
    private final JComboBox<String> matchValueBox = new JComboBox<>();
    private final JComboBox<String> matchRoleBox = new JComboBox<>();

    // Reify decomposition (#92): for a qualifier field of a statement class, the two
    // concerns the "single-ENTITY qualifier" inference used to bundle. Auto = the
    // inferred default; Yes/No = explicit override.
    private static final String[] TRI = {"Auto", "Yes", "No"};
    private final JComboBox<String> subjectDefaultBox = new JComboBox<>(TRI);
    private final JComboBox<String> inDedupKeyBox = new JComboBox<>(TRI);

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(50, 1, 10000, 10));

    private final JLabel recommendationLabel =
            new JLabel("Load method: Auto");

    private final JButton sampleShapeButton =
            new JButton("Sample to set shape");

    private final JButton applyButton =
            new JButton("Apply field source");

    private final JComboBox<FieldSourceType> sourceTypeBox =
            new JComboBox<>(FieldSourceType.values());

    public FieldSourcePanel() {
        super(new BorderLayout(4, 4));
        directionBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value == RuleDirection.ROOT_TO_ITEM) {
                    setText("outgoing — this → value");
                } else if (value == RuleDirection.ITEM_TO_ROOT) {
                    setText("incoming — value → this");
                }
                return this;
            }
        });
        directionBox.setToolTipText(
                "<html>Direction of the property — used for <b>related-object</b> "
                + "(Entity list) fields.<br>Outgoing: this entity has the property. "
                + "Incoming: other entities point here (e.g. stars whose "
                + "constellation P59 = this).</html>");
        // A floating explanation (with an example) per option: shown while you
        // hover each choice in the open dropdown, and on the combo itself for
        // the current selection.
        productionBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof FieldProductionKind k) {
                    list.setToolTipText(productionExplain(k));
                }
                return this;
            }
        });
        productionBox.addActionListener(e -> {
            productionBox.setToolTipText(
                    productionExplain((FieldProductionKind) productionBox.getSelectedItem()));
            refreshCompanionRows();
        });
        productionBox.setToolTipText(
                productionExplain((FieldProductionKind) productionBox.getSelectedItem()));
        shapeBox.setToolTipText(
                "<html>How many values this field holds per entity:<br>"
                + "<b>Single value</b> — at most one (e.g. area, magnitude, "
                + "abbreviation).<br>"
                + "<b>List</b> — possibly many (e.g. a constellation's "
                + "sharesBorderWith / stars, a figure's children or siblings).<br>"
                + "<b>Auto-detect</b> — decide by sampling: shows the \"Sample to "
                + "set shape\" button, which checks real instances and picks "
                + "List if any has more than one value, else Single.<br><br>"
                + "If unsure, leave Auto-detect and click \"Sample to set "
                + "shape\".</html>");
        sampleShapeButton.setToolTipText(
                "<html>Samples real instances for this property and sets the "
                + "shape: <b>List</b> if any instance has more than one value, "
                + "else <b>Single</b>. (Shown when Shape = Auto-detect.)</html>");
        String filterTip =
                "<html>Optional numeric filter on this property — keep only "
                + "entities whose value satisfies it.<br>E.g. <b>apparentMagnitude "
                + "&le; 3</b> keeps only bright/notable stars. \"—\" = no "
                + "filter.</html>";
        filterOpBox.setToolTipText(filterTip);
        filterValueField.setToolTipText(filterTip);
        buildUi();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    public void setPropertyCache(Map<String, WikidataProperty> cache) {
        this.propertyCache = cache == null ? Map.of() : cache;
    }

    public void setProjectModel(GeneratedProjectModel projectModel) {
        this.projectModel = projectModel;
    }

    public void setQueryRunner(SwingQueryRunner queryRunner) {
        this.queryRunner = queryRunner;
        queryRunner.wireButton(
                discoverDbpediaButton,
                this::acceptDbpediaProps,
                this::buildDbpediaPropsQuery,
                ex -> JOptionPane.showMessageDialog(
                        this,
                        "Discover DBpedia properties failed:\n" + ex.getMessage(),
                        "Discover failed",
                        JOptionPane.ERROR_MESSAGE));
    }

    public void onSampleRequested(Runnable r) {
        this.onSampleRequested = r == null ? () -> {} : r;
    }

    public void afterApplyField(Consumer<GeneratedFieldModel> afterApplyField) {
        this.afterApplyField =
                afterApplyField == null ? f -> {} : afterApplyField;
    }

    public void edit(GeneratedFieldModel field) {
        this.field = field;

        if (field == null) {
            clear();
            return;
        }

        FieldSourceMapping m = field.mapping();

        titleLabel.setText("Field: " + field.name());

        fieldNameField.setText(field.name());
        sourceTypeBox.setSelectedItem(m.sourceType());
        typeBox.setSelectedItem(field.type());
        refreshObjectTypeBox(entityClassForDisplay());
        shapeBox.setSelectedItem(field.cardinality());
        renderModeBox.setSelectedItem(field.renderMode());
        requiredBox.setSelected(field.required());
        requiredBox.setEnabled(!field.isNameField());
        edgeMembershipBox.setSelectedItem(field.edgeMembership());
        onlyRelatedOfTypeBox.setSelected(field.edgeMembership()
                != wikidata.explore.model.EdgeMembershipMode.NONE);
        refreshSortFieldBox(field.entityClassName());
        sortFieldBox.setSelectedItem(
                field.hasSort() ? field.sortFieldName() : NO_SORT);
        sortDirBox.setSelectedItem(
                field.sortDescending() ? "descending" : "ascending");
        directionBox.setSelectedItem(m.direction());
        productionBox.setSelectedItem(m.productionKind());
        filterOpBox.setSelectedItem(symbolOf(field.filterOperator()));
        filterValueField.setText(field.filterValue() == null
                ? "" : trimDouble(field.filterValue()));

        propertyPidField.setText(m.propertyPid());
        qualifierPidField.setText(m.qualifierPid());
        subjectDefaultBox.setSelectedItem(triLabel(m.subjectDefault()));
        inDedupKeyBox.setSelectedItem(triLabel(m.inDedupKey()));
        expectationBox.setSelectedItem(field.expectation());
        propertyLabel.setText(m.displayProperty());

        limitSpinner.setValue(Math.max(1, m.limit()));

        refreshCompanionRows();
        updateRecommendation();
        updateSampleButtonState();
    }

    // Populate the COMPANION_MATCH match-field pickers from the owning class's
    // sibling fields, select the saved ones, and enable them only in that mode.
    private void refreshCompanionRows() {
        subjectBox.removeAllItems();
        matchValueBox.removeAllItems();
        matchRoleBox.removeAllItems();

        // Subject may be a sibling field OR the reify "source" — offer both.
        subjectBox.addItem("source");
        GeneratedClassModel owner = ownerClass();
        if (owner != null) {
            for (GeneratedFieldModel f : owner.fields()) {
                if (f == null || f == field) {
                    continue;
                }
                subjectBox.addItem(f.name());
                matchValueBox.addItem(f.name());
                matchRoleBox.addItem(f.name());
            }
        }

        if (field != null) {
            FieldSourceMapping m = field.mapping();
            subjectBox.setSelectedItem(
                    m.subjectField().isEmpty() ? "source" : m.subjectField());
            if (!m.matchValueField().isEmpty()) {
                matchValueBox.setSelectedItem(m.matchValueField());
            }
            if (!m.matchRoleField().isEmpty()) {
                matchRoleBox.setSelectedItem(m.matchRoleField());
            }
        }

        Object pk = productionBox.getSelectedItem();
        boolean companion = pk == FieldProductionKind.COMPANION_MATCH;
        // A DATE field (Auto production) can overlay its value from a referenced
        // date — year ← via.source (e.g. edition.date) — a field-level transform
        // that composes with the field's own source. Enable via (Subject field) +
        // source (Match value field); the source lives on the REFERENCED class, so
        // allow typing it.
        boolean dateProjection = field != null
                && field.type() == FieldType.DATE
                && pk == FieldProductionKind.AUTO;
        subjectBox.setEnabled(companion || dateProjection);
        matchValueBox.setEnabled(companion || dateProjection);
        matchValueBox.setEditable(dateProjection);
        matchValueBox.setToolTipText(dateProjection
                ? "The PATH to project off the reference — e.g. date.year (year), "
                        + "date.monthDay (birthday), or date (the whole date). "
                        + "Extraction is the path, not a convention."
                : null);
        matchRoleBox.setEnabled(companion);
    }

    public void useProperty(String pid, String label) {
        if (field == null) {
            return;
        }

        field.mapping().propertyPid(pid);
        field.mapping().propertyLabel(label);

        propertyPidField.setText(field.mapping().propertyPid());
        propertyLabel.setText(field.mapping().displayProperty());

        autoAdjustFromProperty(pid, label);

        updateRecommendation();
        afterChange.accept(null);
        afterApplyField.accept(field);
    }

    public void applyEdits() { apply(); }

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

        JLabel question = new JLabel("How do we fill this field?");
        question.setFont(question.getFont().deriveFont(Font.ITALIC));
        addWide(form, c, y++, question);

        addRow(form, c, y++, "Field name:", fieldNameField);

        // --- What it holds ---
        typeBox.setToolTipText("What this field holds: Text / Number / Date / "
                + "Image, or an Entity (a domain class — pick it in \"Of class\").");
        addRow(form, c, y++, "Holds:", typeBox);
        objectTypeBox.setToolTipText("The domain class this field refers to "
                + "(when Holds = Entity). The value is a reference to that class's "
                + "instances — clicking navigates to them.");
        addRow(form, c, y++, "Of class:", objectTypeBox);
        addRow(form, c, y++, "Count:", shapeBox);
        addRow(form, c, y++, "Load as:", productionBox);

        // --- Where it comes from ---
        addWide(form, c, y++, sectionLabel("Source"));
        addRow(form, c, y++, "From:", sourceTypeBox);

        JPanel propRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        propRow.add(propertyPidField);
        propRow.add(propertyLabel);
        discoverDbpediaButton.setVisible(false);
        propRow.add(discoverDbpediaButton);
        addRow(form, c, y++, "Property:", propRow);
        qualifierPidField.setToolTipText("<html>For a field of a <b>statement "
                + "reification</b> class (the class \"Reifies statements of\" "
                + "another): the <b>qualifier</b> PID this field draws from "
                + "(e.g. P585 → year, P1686 → for work, P2453 → nominee). "
                + "Blank = a direct/value field.</html>");
        addRow(form, c, y++, "Qualifier of:", qualifierPidField);

        subjectDefaultBox.setToolTipText("<html>Reify (#92): when this qualifier is "
                + "<b>absent</b> on a statement, fill the field with the statement's "
                + "<b>subject</b> (the reify source). <b>Auto</b> = inferred (Yes for a "
                + "single-entity qualifier). Right for <b>nominee</b> (the subject IS the "
                + "nominee) and a dedup-bridge like <b>forWork</b>; set <b>No</b> for a "
                + "plain reference like <b>edition</b> so an absent ceremony stays empty, "
                + "not the film.</html>");
        addRow(form, c, y++, "Subject-default:", subjectDefaultBox);
        inDedupKeyBox.setToolTipText("<html>Reify (#92): whether this field is part of "
                + "the reified record's <b>identity (dedup) key</b>. <b>Auto</b> = "
                + "inferred (Yes for every qualifier except a date).</html>");
        addRow(form, c, y++, "In dedup key:", inDedupKeyBox);

        // COMPANION_MATCH rows: Property = companion property (e.g. P166 award
        // received), Qualifier of = the companion role qualifier (e.g. P1686 for
        // work); Subject/value/role pick which of this record's fields to match on.
        subjectBox.setToolTipText("<html>Companion match: this record's field "
                + "holding the entity that <b>carries</b> the companion statement "
                + "(e.g. <b>nominee</b> — the Oscar win P166 is on the winner). "
                + "<b>source</b> = the reify subject.</html>");
        addRow(form, c, y++, "Subject field:", subjectBox);
        matchValueBox.setToolTipText("<html>Companion match: this record's field "
                + "whose value must equal the companion statement's value "
                + "(e.g. <b>category</b>).</html>");
        addRow(form, c, y++, "Match value field:", matchValueBox);
        matchRoleBox.setToolTipText("<html>Companion match: this record's field "
                + "matched against the companion's role qualifier — or the "
                + "companion subject when that qualifier is absent "
                + "(e.g. <b>source</b> vs the win's for-work/film).</html>");
        addRow(form, c, y++, "Match role field:", matchRoleBox);

        directionBox.setToolTipText("<html>Where the property lives:<br>"
                + "<b>this entity</b> (outgoing, ?this P ?value)<br>"
                + "<b>related entities</b> (incoming, ?other P ?this) — e.g. "
                + "stars whose constellation P59 = this.</html>");
        addRow(form, c, y++, "Found on:", directionBox);

        // --- Refine ---
        addWide(form, c, y++, sectionLabel("Refine"));
        addWide(form, c, y++, requiredBox);

        expectationBox.setToolTipText("<html>Post-transform expectation (#96) for a "
                + "reified/statement class's field. <b>NONE</b> = no check. "
                + "<b>EXPECTED</b> = keep every record but report coverage and surface "
                + "the ones missing this field (a present/missing facet) — use this to "
                + "SEE the gap before deciding. <b>REQUIRED</b> = drop records missing "
                + "the field (only once you've confirmed the missing ones are bad data; "
                + "an absent qualifier is often a legit record).</html>");
        addRow(form, c, y++, "Expectation:", expectationBox);

        JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filterRow.add(filterOpBox);
        filterRow.add(filterValueField);
        addRow(form, c, y++, "Numeric filter:", filterRow);

        onlyRelatedOfTypeBox.setToolTipText("<html>When this field refers to a "
                + "class, keep only related items that ARE that class (P31), "
                + "dropping off-type values reached by the same property.</html>");
        addWide(form, c, y++, onlyRelatedOfTypeBox);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        examplesButton.setToolTipText(
                "Worked, explained config recipes — apply one to this field.");
        applyStatusLabel.setForeground(new Color(0x1a7f37));
        buttons.add(applyStatusLabel);
        buttons.add(examplesButton);
        buttons.add(sampleShapeButton);
        buttons.add(applyButton);
        addWide(form, c, y++, buttons);

        examplesButton.addActionListener(e -> showExamplesDialog());

        add(scroll, BorderLayout.CENTER);

        applyButton.addActionListener(e -> apply());
        sampleShapeButton.addActionListener(e -> onSampleRequested.run());
        sourceTypeBox.addActionListener(e -> updateRecommendation());
        objectTypeBox.addActionListener(e -> {
            if (updatingObjectTypeBox) {
                return;
            }
            if (NEW_CLASS_SENTINEL.equals(objectTypeBox.getSelectedItem())) {
                String name = JOptionPane.showInputDialog(
                        this, "New class name:", "Add class",
                        JOptionPane.PLAIN_MESSAGE);
                if (name != null && !name.isBlank()) {
                    name = GeneratedQuizableSourceGenerator.sanitizeClassName(name.trim());
                    if (projectModel != null) {
                        projectModel.addClass(new GeneratedClassModel(name));
                    }
                    refreshObjectTypeBox(name);
                } else {
                    refreshObjectTypeBox(
                            field != null ? field.entityClassName() : "");
                }
            }
        });

        typeBox.addActionListener(e -> {
            updateRecommendation();
            if (typeBox.getSelectedItem() == FieldType.ENTITY
                    && selectedEntityClass().isBlank()) {
                String propLabel = field != null && field.mapping() != null
                        ? field.mapping().propertyLabel() : "";
                refreshObjectTypeBox(suggestEntityClassName(
                        propLabel == null ? "" : propLabel.toLowerCase()));
            }
        });
        shapeBox.addActionListener(e -> {
            updateRecommendation();
            updateSampleButtonState();
        });
        renderModeBox.addActionListener(e -> updateRecommendation());
    }

    // Tri-state (Auto / Yes / No) ↔ nullable Boolean for the reify overrides.
    private static String triLabel(Boolean b) {
        return b == null ? "Auto" : (b ? "Yes" : "No");
    }

    private static Boolean triValue(JComboBox<String> box) {
        Object s = box.getSelectedItem();
        if ("Yes".equals(s)) {
            return Boolean.TRUE;
        }
        if ("No".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private void apply() {
        if (field == null) {
            return;
        }

        FieldSourceMapping m = field.mapping();
        m.sourceType((FieldSourceType) sourceTypeBox.getSelectedItem());
        m.propertyPid(propertyPidField.getText());
        m.qualifierPid(RuleNode.cleanPid(qualifierPidField.getText()));
        m.subjectDefault(triValue(subjectDefaultBox));
        m.inDedupKey(triValue(inDedupKeyBox));
        Object pk = productionBox.getSelectedItem();
        boolean dateProjection = field.type() == FieldType.DATE
                && pk == FieldProductionKind.AUTO;
        if (pk == FieldProductionKind.COMPANION_MATCH || dateProjection) {
            Object s = subjectBox.getSelectedItem();
            Object v = matchValueBox.getSelectedItem();
            Object r = matchRoleBox.getSelectedItem();
            // "source" is the default subject — store blank so it stays the fallback.
            // For a DATE field, subject = the reference to follow (via) and matchValue
            // = the date field to read off it (source).
            String subj = s == null ? "" : s.toString();
            m.subjectField("source".equals(subj) ? "" : subj);
            m.matchValueField(v == null ? "" : v.toString());
            m.matchRoleField(r == null ? "" : r.toString());
        }
        m.direction((RuleDirection) directionBox.getSelectedItem());

        // autoAdjustFromProperty inspects a WIKIDATA property; skip it for a
        // DBpedia infobox property (e.g. "numbermainstars" isn't a Pxx).
        if (typeBox.getSelectedItem() == FieldType.AUTO
                && m.sourceType() != FieldSourceType.DBPEDIA) {
            autoAdjustFromProperty(m.propertyPid(), m.propertyLabel());
        }

        field.name(fieldNameField.getText());
        field.type((FieldType) typeBox.getSelectedItem());
        field.entityClassName(selectedEntityClass());
        field.cardinality((FieldCardinality) shapeBox.getSelectedItem());
        // Display + "load as" are inferred now (those controls were retired):
        // an entity field is a reference (link) to its class's instances; a
        // scalar shows inline. Per-class generation (#43) populates the refs.
        if (typeBox.getSelectedItem() != FieldType.AUTO) {
            field.renderMode(field.type() == FieldType.ENTITY
                    ? FieldRenderMode.REFERENCE : FieldRenderMode.INLINE);
        }
        field.required(requiredBox.isSelected());
        field.expectation((wikidata.explore.model.FieldExpectation)
                expectationBox.getSelectedItem());
        field.edgeMembership(onlyRelatedOfTypeBox.isSelected()
                ? wikidata.explore.model.EdgeMembershipMode.INHERIT
                : wikidata.explore.model.EdgeMembershipMode.NONE);
        applyNumericFilter(field);

        // The "Load as" dropdown is authoritative — the user's explicit choice
        // (e.g. "Related objects" for a large collection) wins. autoProduction only
        // seeds a sensible DEFAULT into the dropdown when a property is picked.
        m.productionKind((FieldProductionKind) productionBox.getSelectedItem());
        propertyLabel.setText(m.displayProperty());

        titleLabel.setText("Field: " + field.name());

        updateRecommendation();
        flashApplied(field.name());

        afterChange.accept(null);
        afterApplyField.accept(field);
    }

    // Brief "✓ Applied" confirmation so it's obvious the edit took (the change
    // is otherwise only visible as a tree-label update).
    private void flashApplied(String name) {
        applyStatusLabel.setText("✓ Applied " + name);
        javax.swing.Timer timer = new javax.swing.Timer(2500,
                e -> applyStatusLabel.setText(" "));
        timer.setRepeats(false);
        timer.start();
    }

    private void autoAdjustFromProperty(String pid, String label) {
        if (field == null) {
            return;
        }

        String cleanPid = RuleNode.cleanPid(pid);

        WikidataProperty cached = propertyCache.get(cleanPid);
        if (cached != null && !cached.datatype().isBlank()) {
            field.type(WikidataPropertyScore.fieldType(cached));
            field.cardinality(WikidataPropertyScore.fieldCardinality(cached));
            field.renderMode(WikidataPropertyScore.renderMode(cached));
            if (label == null || label.isBlank()) {
                label = cached.label();
            }
        }

        String lower = label == null ? "" : label.toLowerCase();

        if ("P18".equals(cleanPid) || lower.contains("image")) {
            field.type(FieldType.IMAGE);
            field.cardinality(FieldCardinality.SINGLE);
            field.renderMode(FieldRenderMode.INLINE);
        }

        if (field.type() == FieldType.ENTITY && field.entityClassName().isBlank()) {
            field.entityClassName(suggestEntityClassName(lower));
        }

        typeBox.setSelectedItem(field.type());
        shapeBox.setSelectedItem(field.cardinality());
        renderModeBox.setSelectedItem(field.renderMode());
        refreshObjectTypeBox(field.entityClassName());

        updateSampleButtonState();
        autoProduction(field.mapping());            // seed a default…
        productionBox.setSelectedItem(field.mapping().productionKind()); // …show it
    }

    // Per-"Load as" explanation + a concrete example, shown as a floating
    // tooltip while choosing and on the selected value.
    private static String productionExplain(FieldProductionKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case INLINE_VALUE -> "<html><b>Simple property</b> — a literal value "
                    + "stored as-is.<br><i>Example:</i> area = 1303 (number), "
                    + "abbreviation = \"Ori\", birthDate = 1879.</html>";
            case DELAYED_ENTITY_FIELD -> "<html><b>Related entity values</b> — the "
                    + "target entities as id+label <b>references</b>, inlined; "
                    + "clicking one navigates to it if it's in the set. No depth "
                    + "needed.<br><i>Example:</i> father → Zeus; characters → "
                    + "[Heracles, Theseus].</html>";
            case CHILD_OBJECTS -> "<html><b>Related objects</b> — full "
                    + "<b>sub-objects</b> with their own fields, fetched as a "
                    + "separate per-parent query (<b>needs depth ≥ 1</b>).<br>"
                    + "<i>Example:</i> a constellation's stars, each carrying its "
                    + "own apparent magnitude.</html>";
            case INVERT -> "<html><b>Invert</b> — <b>derived</b>, not fetched: the "
                    + "reverse of a forward reference on the referenced class, built "
                    + "in memory from data already generated (no query, no depth, no "
                    + "cycle).<br><i>Example:</i> Category.nominees = the reverse of "
                    + "Oscarnominations.categories.</html>";
            case COMPANION_MATCH -> "<html><b>Companion match</b> — a <b>Boolean</b> "
                    + "that is true iff a companion statement "
                    + "(<i>Companion&nbsp;property</i>[value, <i>Role&nbsp;qualifier</i>]) "
                    + "exists on this record's subject with the same value and role. A "
                    + "generic field-value match, no value fetched.<br><i>Example:</i> "
                    + "Nomination.won = a P166/P1346 award-received companion to the "
                    + "P1411/P2453 nomination.</html>";
            case AUTO -> "<html><b>Auto</b> — decide from the field's type and "
                    + "shape.<br><i>Example:</i> a number → Simple property; an "
                    + "entity list → Related entity values.</html>";
        };
    }

    private void autoProduction(FieldSourceMapping m) {
        if (field == null) {
            return;
        }

        if (field.type() == FieldType.ENTITY && field.collection()) {
            // A collection that references a CLASS is a child-object edge →
            // generate it as a SEPARATE batched query (safe for large sets, gives
            // typed child objects). A bare entity collection (no class) stays an
            // inlined value list — fine for the small sets that suits.
            // Inlining a large class-collection (e.g. a category's 1000+ nominees)
            // via GROUP_CONCAT overflows the named subquery and returns nothing.
            boolean typed = field.entityClassName() != null
                    && !field.entityClassName().isBlank();
            m.productionKind(typed
                    ? FieldProductionKind.CHILD_OBJECTS
                    : FieldProductionKind.DELAYED_ENTITY_FIELD);
        } else if (field.type() == FieldType.IMAGE) {
            m.productionKind(FieldProductionKind.INLINE_VALUE);
        } else {
            m.productionKind(FieldProductionKind.AUTO);
        }
    }

    // Worked, explained recipes: pick one to see its settings + the reason for
    // each, then apply them all to the current field.
    private void showExamplesDialog() {
        if (field == null) {
            return;
        }

        List<FieldRecipe> recipes = FieldRecipes.all();
        JList<FieldRecipe> list = new JList<>(recipes.toArray(new FieldRecipe[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(recipes.size());

        DefaultTableModel stepsModel = new DefaultTableModel(
                new Object[]{"Setting", "Value", "Why"}, 0) {
            @Override public boolean isCellEditable(int r, int col) { return false; }
        };
        JTable steps = new JTable(stepsModel);
        steps.setRowHeight(22);
        steps.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        steps.getColumnModel().getColumn(0).setPreferredWidth(200);
        steps.getColumnModel().getColumn(1).setPreferredWidth(170);
        steps.getColumnModel().getColumn(2).setPreferredWidth(380);

        JLabel summary = new JLabel(" ");
        summary.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JButton applyRecipe = new JButton("Apply to this field");
        applyRecipe.setEnabled(false);

        Runnable refresh = () -> {
            FieldRecipe r = list.getSelectedValue();
            stepsModel.setRowCount(0);
            if (r == null) {
                summary.setText(" ");
                applyRecipe.setEnabled(false);
                return;
            }
            summary.setText("<html><b>" + r.goal() + "</b> — " + r.summary() + "</html>");
            for (FieldRecipe.Step s : r.steps()) {
                stepsModel.addRow(new Object[]{s.setting(), s.value(), s.why()});
            }
            applyRecipe.setEnabled(true);
        };
        list.addListSelectionListener(e -> refresh.run());
        if (!recipes.isEmpty()) {
            list.setSelectedIndex(0);
        }

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(list),
                new JScrollPane(steps));
        split.setDividerLocation(220);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.add(summary, BorderLayout.NORTH);
        center.add(split, BorderLayout.CENTER);

        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(this),
                "Configuration examples",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(0, 6));
        dialog.add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton close = new JButton("Close");
        south.add(applyRecipe);
        south.add(close);
        dialog.add(south, BorderLayout.SOUTH);

        applyRecipe.addActionListener(ev -> {
            FieldRecipe r = list.getSelectedValue();
            if (r != null) {
                r.applyTo(field, projectModel);
                edit(field);                 // reflect the new settings
                afterApplyField.accept(field);
                afterChange.accept(null);
                dialog.dispose();
            }
        });
        close.addActionListener(ev -> dialog.dispose());

        dialog.setPreferredSize(new Dimension(820, 380));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // The class that owns the field being edited (so we can sample its
    // instances for DBpedia property discovery).
    private GeneratedClassModel ownerClass() {
        if (projectModel == null || field == null) {
            return null;
        }
        for (GeneratedClassModel cls : projectModel.classes()) {
            if (cls.fields().contains(field)) {
                return cls;
            }
        }
        return projectModel.rootClass();
    }

    private DiscoverDBpediaPropertiesQuery buildDbpediaPropsQuery() {
        GeneratedClassModel owner = ownerClass();
        String typeQid = owner == null || owner.instanceMapping() == null
                ? "" : owner.instanceMapping().sourceQid();
        if (typeQid == null || !typeQid.matches("Q\\d+")) {
            JOptionPane.showMessageDialog(this,
                    "Set the owning class's Wikidata type first.",
                    "No class type", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return new DiscoverDBpediaPropertiesQuery(typeQid, 8);
    }

    private void acceptDbpediaProps(TableQueryResult result) {
        List<List<Object>> rows = result == null ? List.of() : result.rows();
        SwingUtilities.invokeLater(() -> showDbpediaPropsDialog(rows));
    }

    // Each row: a DBpedia infobox property, how many sampled instances have it,
    // and an example. Clicking "Use" fills the Property field.
    private void showDbpediaPropsDialog(List<List<Object>> rows) {
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No DBpedia infobox properties found for this class.",
                    "Discover properties", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String[] cols = {"Property", "Have", "Example"};
        Object[][] data = new Object[rows.size()][3];
        for (int i = 0; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            for (int j = 0; j < 3; j++) {
                data[i][j] = j < r.size() ? r.get(j) : "";
            }
        }

        DefaultTableModel model = new DefaultTableModel(data, cols) {
            @Override public boolean isCellEditable(int r, int col) { return false; }
        };
        JTable table = new JTable(model);
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(50);
        table.getColumnModel().getColumn(2).setPreferredWidth(320);

        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(660, 320));

        JButton useButton = new JButton("Use selected property");
        useButton.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e ->
                useButton.setEnabled(table.getSelectedRow() >= 0));

        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(this),
                "DBpedia infobox properties",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(0, 6));
        dialog.add(new JLabel(
                "  \"Have\" = how many sampled instances carry the property."),
                BorderLayout.NORTH);
        dialog.add(sp, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton closeButton = new JButton("Close");
        south.add(useButton);
        south.add(closeButton);
        dialog.add(south, BorderLayout.SOUTH);

        useButton.addActionListener(ev -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                propertyPidField.setText(String.valueOf(table.getValueAt(row, 0)));
                propertyLabel.setText("(DBpedia infobox property)");
                dialog.dispose();
            }
        });
        closeButton.addActionListener(ev -> dialog.dispose());

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void updateRecommendation() {
        if (field == null) {
            recommendationLabel.setText(" ");
            return;
        }
        FieldSourceType sourceType =
                (FieldSourceType) sourceTypeBox.getSelectedItem();

        discoverDbpediaButton.setVisible(
                sourceType == FieldSourceType.DBPEDIA && queryRunner != null);

        if (sourceType != null && !sourceType.implementedNow()) {
            recommendationLabel.setText(
                    "Source: " + sourceType + " | Not yet implemented");
            return;
        }

        if (sourceType == FieldSourceType.DBPEDIA) {
            recommendationLabel.setText(
                    "DBpedia: 'Property' = the Wikipedia infobox property "
                    + "(e.g. numbermainstars, brighteststarname). Joined by "
                    + "owl:sameAs to the entity's Wikidata QID, after extraction.");
            return;
        }

        FieldType type = (FieldType) typeBox.getSelectedItem();
        FieldCardinality card =
                (FieldCardinality) shapeBox.getSelectedItem();
        FieldRenderMode renderMode =
                (FieldRenderMode) renderModeBox.getSelectedItem();

        String text;

        if (type == FieldType.ENTITY && card == FieldCardinality.COLLECTION) {
            text = "Load method: related entity values, loaded after root instances";
        } else if (type == FieldType.IMAGE) {
            text = "Load method: media field";
        } else {
            text = "Load method: Auto";
        }

        if (renderMode == FieldRenderMode.REFERENCE) {
            text += " | Render: clickable references";
        } else if (renderMode == FieldRenderMode.INLINE) {
            text += " | Render: inline";
        }

        recommendationLabel.setText(text);
    }

    private void updateSampleButtonState() {
        boolean autoShape = shapeBox.getSelectedItem() == FieldCardinality.AUTO;
        sampleShapeButton.setVisible(autoShape);
    }

    private String entityClassForDisplay() {
        if (field == null) {
            return "";
        }
        String cls = field.entityClassName();
        if (!cls.isBlank()) {
            return cls;
        }
        if (field.type() != FieldType.ENTITY) {
            return "";
        }
        String propLabel = field.mapping() == null ? "" : field.mapping().propertyLabel();
        return suggestEntityClassName(propLabel == null ? "" : propLabel.toLowerCase());
    }

    private String suggestEntityClassName(String lower) {
        if (projectModel != null && (
                lower.contains("border")
                        || lower.contains("neighbour")
                        || lower.contains("neighbor"))) {
            return GeneratedQuizableSourceGenerator.sanitizeClassName(
                    projectModel.rootClass().className());
        }
        if (field != null && !field.name().isBlank()) {
            return GeneratedQuizableSourceGenerator.sanitizeClassName(singularOf(field.name()));
        }
        return "";
    }

    private static String singularOf(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        s = s.trim();
        if (s.endsWith("ies") && s.length() > 3) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.endsWith("s") && s.length() > 1) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    // Shown/selected when the field has no object type (e.g. a scalar field
    // like name or apparent magnitude) — otherwise the box would default to
    // the first class and misleadingly show "Constellation".
    private static final String NO_TYPE = "(none)";

    // Populates the "Sort children by" box with the child class's own
    // (non-name) field names, so you can sort the edge's entities by e.g.
    // apparentMagnitude. Only meaningful for a child-object field.
    private void refreshSortFieldBox(String childClassName) {
        sortFieldBox.removeAllItems();
        sortFieldBox.addItem(NO_SORT);
        if (projectModel != null && childClassName != null
                && !childClassName.isBlank()) {
            GeneratedClassModel cls = projectModel.findClass(childClassName);
            if (cls != null) {
                for (GeneratedFieldModel f : cls.fields()) {
                    if (f != null && !f.isNameField()) {
                        sortFieldBox.addItem(f.name());
                    }
                }
            }
        }
    }

    private void refreshObjectTypeBox(String selectedClass) {
        updatingObjectTypeBox = true;
        try {
        objectTypeBox.removeAllItems();
        objectTypeBox.addItem(NO_TYPE);
        if (projectModel != null) {
            for (GeneratedClassModel cls : projectModel.classes()) {
                objectTypeBox.addItem(cls.className());
            }
        }
        objectTypeBox.addItem(NEW_CLASS_SENTINEL);
        if (selectedClass != null && !selectedClass.isBlank()
                && !NEW_CLASS_SENTINEL.equals(selectedClass)) {
            // Add if not already in the list (e.g., a class not yet in registry)
            boolean found = false;
            for (int i = 0; i < objectTypeBox.getItemCount(); i++) {
                if (selectedClass.equals(objectTypeBox.getItemAt(i))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                objectTypeBox.insertItemAt(selectedClass, objectTypeBox.getItemCount() - 1);
            }
            objectTypeBox.setSelectedItem(selectedClass);
        } else {
            objectTypeBox.setSelectedItem(NO_TYPE);
        }
        } finally {
            updatingObjectTypeBox = false;
        }
    }

    private String selectedEntityClass() {
        Object sel = objectTypeBox.getSelectedItem();
        if (sel == null || NO_TYPE.equals(sel) || NEW_CLASS_SENTINEL.equals(sel)) {
            return "";
        }
        return (String) sel;
    }

    private void clear() {
        titleLabel.setText("Field");
        fieldNameField.setText("");
        refreshObjectTypeBox("");
        propertyPidField.setText("");
        propertyLabel.setText("(not selected)");
        renderModeBox.setSelectedItem(FieldRenderMode.AUTO);
        requiredBox.setSelected(false);
        expectationBox.setSelectedItem(wikidata.explore.model.FieldExpectation.NONE);
        directionBox.setSelectedItem(RuleDirection.ITEM_TO_ROOT);
        productionBox.setSelectedItem(FieldProductionKind.AUTO);
        filterOpBox.setSelectedItem(NO_OP);
        filterValueField.setText("");
        recommendationLabel.setText(" ");
    }

    // Reads the operator + value controls onto the field. Invalid/empty = no
    // filter.
    private void applyNumericFilter(GeneratedFieldModel field) {
        wikidata.explore.filter.WikidataValueFilterOperator op =
                operatorOf((String) filterOpBox.getSelectedItem());
        Double value = null;
        String txt = filterValueField.getText().trim();
        if (!txt.isEmpty()) {
            try {
                value = Double.parseDouble(txt);
            } catch (NumberFormatException ignored) {
            }
        }
        if (op == null || value == null) {
            field.filterOperator(null);
            field.filterValue(null);
        } else {
            field.filterOperator(op);
            field.filterValue(value);
        }
    }

    private static wikidata.explore.filter.WikidataValueFilterOperator operatorOf(String symbol) {
        if (symbol == null) return null;
        for (wikidata.explore.filter.WikidataValueFilterOperator o
                : wikidata.explore.filter.WikidataValueFilterOperator.values()) {
            if (o.sparql().equals(symbol)) return o;
        }
        return null;
    }

    private static String symbolOf(wikidata.explore.filter.WikidataValueFilterOperator op) {
        return op == null ? NO_OP : op.sparql();
    }

    private static String trimDouble(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    // A small bold section divider ("Source", "Refine").
    private static JComponent sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        l.setForeground(new Color(0x55, 0x55, 0x55));
        l.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        return l;
    }

    private static void addRow(
            JPanel form,
            GridBagConstraints c,
            int y,
            String label,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(new JLabel(label), c);

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