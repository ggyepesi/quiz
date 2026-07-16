package wikidata.explore.workbench;

import aux.GridBagUtils;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementFieldSemantics;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.ModelStatementReifications.Reification;
import wikidata.explore.transform.QualifierLoadConfig;
import wikidata.explore.transform.ReifyConstruct;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configures a STATEMENT class (derived/natural-key identity): its instances are
 * the statements of a property on each member of a SOURCE class — e.g.
 * Nomination = the P1411 statements of OscarNominations.
 *
 * <p>This is intentionally separate from {@link ClassSourcePanel}, which
 * configures qid-identity source classes. Qualifier-to-field mappings remain in
 * {@link FieldSourcePanel}; this panel owns the statement source and the
 * class-level canonical identity.</p>
 */
public class StatementSourcePanel extends JPanel {

    private GeneratedClassModel clazz;
    private GeneratedProjectModel projectModel;

    private Consumer<Void> afterChange = ignored -> {};
    private Supplier<List<String>> sourceClassCandidates = List::of;

    private final JLabel titleLabel = new JLabel("Statement class");
    private final JTextField classNameField = new JTextField(18);
    private final JComboBox<String> reifyFromBox = new JComboBox<>();
    private final JTextField statementPropField =
            new JTextField("P1411", 6);
    private final JTextField valueTypeField = new JTextField(10);

    private final JPanel keyFieldsPanel =
            new JPanel(new GridBagLayout());
    private final Map<String, JCheckBox> keyFieldBoxes =
            new LinkedHashMap<>();

    private final JComboBox<String> displayNameFieldBox =
            new JComboBox<>();

    private final JLabel identityValue = new JLabel(" ");
    private final JLabel subjectFallbackValue = new JLabel(" ");
    private final JLabel statementValueFallbackValue =
            new JLabel(" ");
    private final JLabel dedupValue = new JLabel(" ");

    private final JTextArea qualifiersArea =
            new JTextArea(5, 30);

    public StatementSourcePanel() {
        buildUi();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange =
                afterChange == null ? ignored -> {} : afterChange;
    }

    public void sourceClassCandidates(
            Supplier<List<String>> candidates) {

        sourceClassCandidates =
                candidates == null ? List::of : candidates;
    }

    /**
     * The project is needed both to resolve source-class names and to derive the
     * exact runtime reification recipe shown in the read-only section.
     */
    public void setProjectModel(
            GeneratedProjectModel projectModel) {

        this.projectModel = projectModel;
    }

    public void edit(GeneratedClassModel clazz) {
        this.clazz = clazz;

        if (clazz == null) {
            clear();
            return;
        }

        titleLabel.setText(
                "Statement class: " + clazz.className());
        classNameField.setText(clazz.className());

        refreshSourceClassChoices();

        StatementClassSource source =
                clazz.statementSource();

        reifyFromBox.setSelectedItem(
                source == null
                        ? ""
                        : source.sourceClassName());

        statementPropField.setText(
                source == null
                        || source.propertyPid().isBlank()
                        ? "P1411"
                        : source.propertyPid());

        // The value-type filter remains part of the statement class's
        // extraction mapping: it constrains the ps: value by P31.
        valueTypeField.setText(
                clazz.instanceMapping().sourceQid());

        rebuildCanonicalControls();
        refreshDerived();
    }

    public void applyEdits() {
        if (clazz == null) {
            return;
        }

        clazz.className(classNameField.getText().trim());

        String sourceClass =
                selectedText(reifyFromBox);
        String statementPid =
                RuleNode.cleanPid(
                        statementPropField.getText());

        clazz.statementSource(
                sourceClass.isBlank()
                        ? null
                        : new StatementClassSource(
                        sourceClass,
                        statementPid));

        clazz.instanceMapping().sourceQid(
                RuleNode.cleanQid(
                        valueTypeField.getText()));

        applyCanonicalControls();
        refreshDerived();
        afterChange.accept(null);
    }

    private void refreshSourceClassChoices() {
        String selected =
                selectedText(reifyFromBox);

        reifyFromBox.removeAllItems();
        reifyFromBox.addItem("");

        for (String name : sourceClassCandidates.get()) {
            if (name == null
                    || name.isBlank()
                    || clazz != null
                    && name.equals(clazz.className())) {
                continue;
            }

            reifyFromBox.addItem(name);
        }

        if (!selected.isBlank()) {
            reifyFromBox.setSelectedItem(selected);
        }
    }

    /**
     * Builds the class-level grain editor. Only fields that can exist when the
     * statement record is first constructed are candidates: scalar, non-name,
     * AUTO-production fields. COMPANION_MATCH fields such as Oscar
     * {@code won} are post-transform facts and must never enter identity.
     */
    private void rebuildCanonicalControls() {
        keyFieldsPanel.removeAll();
        keyFieldBoxes.clear();

        displayNameFieldBox.removeAllItems();
        displayNameFieldBox.addItem("");

        if (clazz == null) {
            keyFieldsPanel.revalidate();
            keyFieldsPanel.repaint();
            return;
        }

        CanonicalSpec canonical =
                clazz.effectiveCanonical();

        int row = 0;
        for (GeneratedFieldModel field : clazz.fields()) {
            if (!StatementFieldSemantics.isCanonicalKeyCandidate(field)) {
                continue;
            }

            JCheckBox box =
                    new JCheckBox(field.name());
            box.setSelected(
                    canonical.keyFields()
                             .contains(field.name()));

            GridBagConstraints c =
                    new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row++;
            c.weightx = 1.0;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(1, 2, 1, 2);

            keyFieldsPanel.add(box, c);
            keyFieldBoxes.put(field.name(), box);
            displayNameFieldBox.addItem(field.name());
        }

        if (canonical.displayNameMode()
                == CanonicalSpec.DisplayNameMode.FIELD) {
            displayNameFieldBox.setSelectedItem(
                    canonical.displayNameField());
        }

        if (row == 0) {
            GridBagConstraints c =
                    new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1.0;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;

            keyFieldsPanel.add(
                    new JLabel(
                            "No scalar statement fields are available yet."),
                    c);
        }

        keyFieldsPanel.revalidate();
        keyFieldsPanel.repaint();
    }

    private void applyCanonicalControls() {
        CanonicalSpec canonical =
                clazz.hasCanonical()
                        ? clazz.canonical()
                        : clazz.effectiveCanonical().copy();

        canonical.kind(CanonicalSpec.Kind.DERIVED);
        canonical.keyFields().clear();

        for (Map.Entry<String, JCheckBox> entry
                : keyFieldBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                canonical.keyFields().add(entry.getKey());
            }
        }

        String displayField =
                selectedText(displayNameFieldBox);

        if (displayField.isBlank()) {
            canonical.displayNameMode(
                    CanonicalSpec.DisplayNameMode.LABEL);
            canonical.displayNameField("");
        } else {
            canonical.displayNameMode(
                    CanonicalSpec.DisplayNameMode.FIELD);
            canonical.displayNameField(displayField);
        }

        clazz.canonical(canonical);
    }

    /**
     * Renders the actual runtime recipe, not a second approximation of it.
     * This keeps the editor's explanation aligned with generation.
     */
    private void refreshDerived() {
        if (clazz == null) {
            return;
        }

        Reification reification =
                projectModel == null
                        ? null
                        : ModelStatementReifications
                          .deriveOne(
                                  clazz,
                                  projectModel);

        if (reification == null) {
            identityValue.setText(
                    "derived natural key");
            subjectFallbackValue.setText("—");
            statementValueFallbackValue.setText("—");
            dedupValue.setText("—");
            qualifiersArea.setText(
                    " (not a reifying class yet — set "
                            + "\"Reify from\" and a statement property, "
                            + "then add qualifier fields)");
            return;
        }

        ReifyConstruct reify =
                reification.reify();
        QualifierLoadConfig load =
                reification.load();

        List<String> subjectFallbacks =
                new ArrayList<>();
        List<String> valueFallbacks =
                new ArrayList<>();

        for (ReifyConstruct.Role role
                : reify.roles()) {
            if (role.fallbackToSource()) {
                subjectFallbacks.add(
                        role.field());
            } else if (load.valueField()
                           .equals(role.from())
                    && !role.field()
                            .equals(role.from())) {
                valueFallbacks.add(
                        role.field());
            }
        }

        // The value role is explicit: the non-qualifier field on the statement PID.
        // When none is mapped, deriveOne falls back to the string "value" (a field
        // that doesn't exist) — flag that here rather than showing "value" as if a
        // field were configured (mirrors the validator warning).
        String explicitValue =
                StatementFieldSemantics.statementValueFieldName(clazz);
        String valuePart = explicitValue.isEmpty()
                ? " · ⚠ no value field — map a non-qualifier field to "
                        + load.propertyPid()
                : " · statement value = " + explicitValue;
        identityValue.setText(
                "derived natural key" + valuePart
                        + (reify.canonicalizesByList()
                        ? " · canonical list = "
                          + reify.primaryListField()
                        : ""));
        identityValue.setForeground(explicitValue.isEmpty()
                ? new java.awt.Color(0xB0, 0x00, 0x20)
                : javax.swing.UIManager.getColor("Label.foreground"));

        subjectFallbackValue.setText(
                display(subjectFallbacks));

        statementValueFallbackValue.setText(
                display(valueFallbacks));

        dedupValue.setText(
                reify.dedupBy().isEmpty()
                        ? "—"
                        : String.join(
                        " + ",
                        reify.dedupBy()));

        StringBuilder qualifiers =
                new StringBuilder();

        for (QualifierLoadConfig.Qualifier qualifier
                : load.qualifiers()) {
            qualifiers.append(" ")
                      .append(
                              qualifier.fieldName())
                      .append(" ← ")
                      .append(qualifier.pid());

            if (qualifier.multi()) {
                qualifiers.append(" (list)");
            }
            if (qualifier.kind()
                    == QualifierLoadConfig.Kind.YEAR) {
                qualifiers.append(" (date)");
            }

            qualifiers.append('\n');
        }

        qualifiersArea.setText(
                qualifiers.length() == 0
                        ? " (no qualifier fields yet)"
                        : qualifiers.toString());
    }

    private void rederiveIdentity() {
        if (clazz == null) {
            return;
        }

        // Clearing the explicit spec asks GeneratedClassModel to infer the grain
        // from the current source fields. The checkboxes then make the inferred
        // result visible and editable before it is saved again.
        clazz.canonical(null);
        rebuildCanonicalControls();
        refreshDerived();
        afterChange.accept(null);
    }

    private void clear() {
        titleLabel.setText("Statement class");
        classNameField.setText("");
        reifyFromBox.removeAllItems();
        statementPropField.setText("P1411");
        valueTypeField.setText("");
        keyFieldsPanel.removeAll();
        keyFieldBoxes.clear();
        displayNameFieldBox.removeAllItems();
        identityValue.setText(" ");
        subjectFallbackValue.setText(" ");
        statementValueFallbackValue.setText(" ");
        dedupValue.setText(" ");
        qualifiersArea.setText("");
    }

    private void buildUi() {
        JPanel form =
                new JPanel(new GridBagLayout());
        form.setBorder(
                BorderFactory.createEmptyBorder(
                        6,
                        6,
                        6,
                        6));

        int row = 0;

        titleLabel.setFont(
                titleLabel.getFont()
                          .deriveFont(
                                  Font.BOLD,
                                  16f));
        GridBagUtils.wideRow(form, row++, titleLabel);

        JLabel explanation =
                new JLabel(
                        "Instances are statements of a property "
                                + "on members of a source class.");
        explanation.setFont(
                explanation.getFont()
                           .deriveFont(Font.ITALIC));
        GridBagUtils.wideRow(form, row++, explanation);

        GridBagUtils.labeledRow(form, row++,
            "Class name:",
            classNameField);

        reifyFromBox.setToolTipText(
                "The source class whose statements become "
                        + "instances of this class.");
        GridBagUtils.labeledRow(form, row++,
            "Reify from:",
            reifyFromBox);

        statementPropField.setToolTipText(
                "The property whose statements are promoted "
                        + "to records, e.g. P1411.");
        GridBagUtils.labeledRow(form, row++,
            "Statement property:",
            statementPropField);

        valueTypeField.setToolTipText(
                "Optional P31 filter on the statement value.");
        GridBagUtils.labeledRow(form, row++,
            "Value type filter:",
            valueTypeField);

        JPanel canonical =
                new JPanel(new GridBagLayout());
        canonical.setBorder(
                BorderFactory.createTitledBorder(
                        "Canonical identity"));

        GridBagConstraints cc =
                new GridBagConstraints();
        cc.insets = new Insets(3, 4, 3, 4);
        cc.anchor = GridBagConstraints.NORTHWEST;
        cc.fill = GridBagConstraints.HORIZONTAL;

        GridBagUtils.labeledRow(canonical, cc, 0,
            "Key fields:",
            keyFieldsPanel);

        displayNameFieldBox.setToolTipText(
                "Single field used as the record's display name.");
        GridBagUtils.labeledRow(canonical, cc, 1,
            "Display-name field:",
            displayNameFieldBox);

        JButton rederive =
                new JButton("Re-derive identity");
        rederive.setToolTipText(
                "Infer the key again from the current scalar "
                        + "AUTO-produced statement fields.");
        rederive.addActionListener(
                event -> rederiveIdentity());

        GridBagUtils.wideRow(canonical, 2, rederive);
        GridBagUtils.wideRow(form, row++, canonical);

        JPanel buttons =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT,
                                6,
                                0));

        JButton refresh =
                new JButton("Refresh derived view");
        refresh.addActionListener(
                event -> {
                    applyEdits();
                    refreshDerived();
                });

        buttons.add(refresh);
        GridBagUtils.wideRow(form, row++, buttons);

        JPanel derived =
                new JPanel(new GridBagLayout());
        derived.setBorder(
                BorderFactory.createTitledBorder(
                        "Derived runtime recipe"));

        GridBagConstraints dc =
                new GridBagConstraints();
        dc.insets = new Insets(3, 4, 3, 4);
        dc.anchor = GridBagConstraints.WEST;
        dc.fill = GridBagConstraints.HORIZONTAL;

        int derivedRow = 0;

        GridBagUtils.labeledRow(derived, dc, derivedRow++,
            "Identity:",
            identityValue);

        subjectFallbackValue.setToolTipText(
                "Qualifier fields that use the statement "
                        + "subject when absent.");
        GridBagUtils.labeledRow(derived, dc, derivedRow++,
            "Subject fallback:",
            subjectFallbackValue);

        statementValueFallbackValue.setToolTipText(
                "Qualifier fields that use the statement's "
                        + "main ps: value when absent.");
        GridBagUtils.labeledRow(derived, dc, derivedRow++,
            "Value fallback:",
            statementValueFallbackValue);

        GridBagUtils.labeledRow(derived, dc, derivedRow++,
            "Canonical key:",
            dedupValue);

        qualifiersArea.setEditable(false);
        qualifiersArea.setBorder(
                BorderFactory.createTitledBorder(
                        "Qualifier fields"));

        GridBagUtils.wideRow(derived, derivedRow++,
             qualifiersArea);
        GridBagUtils.wideRow(form, row++, derived);

        GridBagConstraints filler =
                new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = row;
        filler.weighty = 1.0;
        filler.fill = GridBagConstraints.BOTH;
        filler.gridwidth = 2;
        form.add(new JLabel(), filler);

        setLayout(new BorderLayout());

        JScrollPane scroll =
                new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar()
              .setUnitIncrement(16);

        add(scroll, BorderLayout.CENTER);
    }

    private static String display(
            List<String> values) {

        return values == null || values.isEmpty()
                ? "—"
                : String.join(", ", values);
    }

    private static String selectedText(
            JComboBox<String> box) {

        Object selected =
                box.getSelectedItem();
        return selected == null
                ? ""
                : selected.toString().trim();
    }
}
