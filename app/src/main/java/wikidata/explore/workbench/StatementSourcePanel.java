package wikidata.explore.workbench;

import datasource.schema.FieldType;

import objectview.utils.swing.GridBagUtils;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphExpansionPolicy;
import wikidata.WikidataIds;
import wikidata.explore.model.EntityBound;
import wikidata.explore.generation.WikidataGraphDiscoveryState;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.codegen.GeneratedViewableSourceGenerator;
import wikidata.explore.model.StatementCanonicalDefaults;
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
import java.util.function.BiPredicate;
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
    private BiPredicate<List<String>, List<String>> identityChangeConfirmation =
            this::confirmIdentityChange;

    private static final String ANY = "Anything";
    private static final String THESE_ENTITIES = "These entities";
    private static final String INSTANCES_OF = "Instances of";
    private static final String A_VOCABULARY = "A vocabulary";

    private final JLabel titleLabel = new JLabel("Statement class");
    /** The "no declaration — infer it" choice, shown instead of a blank row. */
    private static final String INFER_PRIMARY_LIST = "(infer)";

    private final JTextField classNameField = new JTextField(18);
    private final JComboBox<String> reifyFromBox = new JComboBox<>();
    private final JTextField statementPropField =
            new JTextField("P1411", 6);
    private static final String NO_VALUE_DOMAIN = "(none)";
    private final JComboBox<String> valueDomainBox = new JComboBox<>();
    private final JLabel subjectValue = new JLabel(" ");
    private final JLabel objectValue = new JLabel(" ");
    // One control per end, so the alternatives cannot be configured together. They used
    // to be separate rows that looked combinable while the loader silently kept one.
    private final JComboBox<String> subjectBoundMode = new JComboBox<>(new String[] {
            ANY, THESE_ENTITIES, INSTANCES_OF });
    private final JTextField subjectBoundValue = new JTextField(16);
    private final JComboBox<String> objectBoundMode = new JComboBox<>(new String[] {
            ANY, A_VOCABULARY, INSTANCES_OF });
    // The object's value is a vocabulary NAME or a type QID depending on the mode, so
    // one slot swaps its editor rather than two rows sitting there half-disabled.
    private final JPanel objectValueHolder = new JPanel(new java.awt.CardLayout());
    private final JTextField valueTypeField = new JTextField(10);
    private final JComboBox<GraphExpansionPolicy> graphExpansionBox =
            new JComboBox<>(GraphExpansionPolicy.values());
    private final JLabel graphPatternValue = new JLabel(" ");

    private final JPanel keyFieldsPanel =
            new JPanel(new GridBagLayout());
    private final Map<String, JCheckBox> keyFieldBoxes =
            new LinkedHashMap<>();
    private final JComboBox<CanonicalSpec.DuplicatePolicy> duplicatePolicyBox =
            new JComboBox<>(CanonicalSpec.DuplicatePolicy.values());

    private final JComboBox<String> displayNameFieldBox =
            new JComboBox<>();
    /** #92: which collection field marks the canonical copy of a shared statement.
     *  "" = infer structurally (right when there is exactly one candidate). */
    private final JComboBox<String> primaryListFieldBox =
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

        refreshValueDomainChoices(source == null ? "" : source.valueSelectionName());

        // The value-type filter remains part of the statement class's
        // extraction mapping: it constrains the ps: value by P31.
        valueTypeField.setText(
                clazz.instanceMapping().sourceQid());
        graphExpansionBox.setSelectedItem(source == null
                ? GraphExpansionPolicy.NONE : source.graphExpansionPolicy());

        showBounds(source, clazz);
        refreshTriple();
        rebuildCanonicalControls();
        refreshDerived();
    }

    /**
     * Puts each end's bound into its one control.
     *
     * <p>The object's bound still lives in two authored places — a vocabulary name and
     * the class's type QID — so this reads whichever is set. The control writes only
     * one and clears the other, which is what makes "both configured" unreachable from
     * here; a model that already has both is a validation error, not something this
     * panel silently resolves.
     */
    private void showBounds(StatementClassSource source, GeneratedClassModel owner) {
        EntityBound subject = source == null
                ? EntityBound.unbounded() : source.subjectBound();
        subjectBoundMode.setSelectedItem(switch (subject.kind()) {
            case EXPLICIT -> THESE_ENTITIES;
            case RELATION -> INSTANCES_OF;
            case UNBOUNDED -> ANY;
        });
        subjectBoundValue.setText(String.join(" ", subject.qids()));

        String vocabulary = source == null ? "" : source.valueSelectionName();
        String typeQid = owner == null ? "" : owner.instanceMapping().sourceQid();
        objectBoundMode.setSelectedItem(!vocabulary.isBlank() ? A_VOCABULARY
                : WikidataIds.isQid(typeQid) ? INSTANCES_OF : ANY);
        refreshBoundControls();
    }

    /** The bound a mode and its typed value describe; unbounded when nothing usable. */
    private static EntityBound boundFromControls(String mode, String text) {
        List<String> qids = new ArrayList<>();
        for (String part : (text == null ? "" : text).split("[,\\s]+")) {
            if (!part.isBlank()) qids.add(part.trim());
        }
        if (THESE_ENTITIES.equals(mode)) return EntityBound.explicit(qids);
        if (INSTANCES_OF.equals(mode)) {
            return qids.isEmpty() ? EntityBound.unbounded()
                    : EntityBound.instancesOf(qids.get(0));
        }
        return EntityBound.unbounded();
    }

    /** A mode beside the value it takes, as one row. */
    private static JPanel boundRow(JComboBox<String> mode, java.awt.Component value) {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 6);
        c.gridx = 0;
        row.add(mode, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        row.add(value, c);
        return row;
    }

    /**
     * Shows only the value the selected mode actually takes.
     *
     * <p>The alternatives were separate rows that looked combinable — a vocabulary and a
     * type filter, both visible, both editable — while only one of them ever reached the
     * query. One mode with one value is what makes that state unreachable rather than
     * merely discouraged.
     */
    private void refreshBoundControls() {
        subjectBoundValue.setEnabled(!ANY.equals(selectedText(subjectBoundMode)));
        String objectMode = selectedText(objectBoundMode);
        ((java.awt.CardLayout) objectValueHolder.getLayout()).show(
                objectValueHolder, A_VOCABULARY.equals(objectMode) ? "vocabulary"
                        : INSTANCES_OF.equals(objectMode) ? "type" : "none");
    }

    /**
     * The triple's two entity legs, shown whether or not they are configured.
     *
     * <p>An unconfigured leg is a STATE, not an absence: a model may legitimately declare
     * a triple with neither end settled and leave that to the domain that generates it.
     * Hiding an unset leg is what made the subject invisible in the editor named after
     * the statement — every other leg was on screen and that one was configured, if at
     * all, from the field editor.
     */
    private void refreshTriple() {
        subjectValue.setText(describeLeg(
                StatementFieldSemantics.statementSubjectFieldName(clazz),
                "filled from the statement's own item"));
        objectValue.setText(describeLeg(
                StatementFieldSemantics.statementValueFieldName(clazz),
                "the value the statement points at"));
    }

    /** "field — Class" for a settled leg, or what it would hold if it were settled. */
    private String describeLeg(String fieldName, String whatItWouldHold) {
        if (fieldName == null || fieldName.isBlank()) {
            return "<html><i>Not configured</i> — " + whatItWouldHold
                    + ". A domain must settle this before it can generate.</html>";
        }
        GeneratedFieldModel field = clazz.fields().stream()
                .filter(candidate -> candidate != null
                        && fieldName.equals(candidate.name()))
                .findFirst().orElse(null);
        String target = field == null ? "" : field.entityClassName();
        return "<html><b>" + fieldName + "</b>"
                + (target == null || target.isBlank()
                        ? " — <i>any entity</i> (no class named; served as a reference)"
                        : " — " + target)
                + "</html>";
    }

    public void applyEdits() {
        if (clazz == null) {
            return;
        }

        String previousClassName = clazz.className();
        String typedClassName = classNameField.getText() == null
                ? "" : classNameField.getText().trim();
        if (typedClassName.isBlank()) {
            showRenameError("A class name is required.");
            classNameField.setText(clazz.className());
        } else {
            String requestedClassName = GeneratedViewableSourceGenerator
                    .sanitizeClassName(typedClassName);
            if (projectModel == null) {
                throw new IllegalStateException(
                        "A Statement class can only be renamed inside a project model.");
            }
            if (!projectModel.renameClass(previousClassName, requestedClassName)) {
                showRenameError("A class or vocabulary/population named '"
                        + requestedClassName + "' already exists.");
                classNameField.setText(clazz.className());
            }
        }
        // Show the canonical spelling chosen by the same sanitizer used by source
        // generation. More importantly, rename through the project: class names are
        // references in field targets, base classes, role selections and kind rules,
        // so changing only this object leaves a partially renamed model.
        classNameField.setText(clazz.className());

        String sourceClass =
                selectedText(reifyFromBox);
        String statementPid =
                RuleNode.cleanPid(
                        statementPropField.getText());

        boolean wasStatementClass = clazz.reifiesStatements();

        // The source class is OPTIONAL: a blank one means subjects are discovered
        // directly from the statement property (guarded by a value domain). Only a
        // blank property AND blank source class means "not a statement class" — a
        // set property alone is enough, so we must NOT null the source in that case
        // (doing so silently reverted a discovered-subject statement class, e.g.
        // the Oscars Nomination, to a plain class on every applyEdits).
        if (statementPid.isBlank() && sourceClass.isBlank()) {
            clazz.statementSource(null);
        } else {
            // Copying the prior source carries the declarations this panel does not
            // edit — the value Selection (VOCABULARY domain) among them — so they
            // cannot be dropped here by being forgotten.
            StatementClassSource prior = clazz.statementSource();
            StatementClassSource next = prior == null
                    ? new StatementClassSource(sourceClass, statementPid)
                    : prior.copy();
            next.sourceClassName(sourceClass);
            next.propertyPid(statementPid);
            next.subjectBound(boundFromControls(
                    selectedText(subjectBoundMode), subjectBoundValue.getText()));

            // ONE object bound is written and the other place is cleared. Writing both
            // is how a model reached the state where a type filter sat beside a
            // vocabulary doing nothing, so this control cannot produce it.
            String objectMode = selectedText(objectBoundMode);
            String valueDomain = selectedText(valueDomainBox);
            next.valueSelectionName(A_VOCABULARY.equals(objectMode)
                    && !NO_VALUE_DOMAIN.equals(valueDomain) ? valueDomain : "");

            next.graphExpansionPolicy((GraphExpansionPolicy)
                    graphExpansionBox.getSelectedItem());
            clazz.statementSource(next);
        }

        // A kind transition is a model-creation event, so this is the correct
        // place to materialize the initial proposal. Reopening or recompiling an
        // existing statement class never runs this branch and therefore never
        // overwrites a user-edited key. Rebuild the checkboxes so the newly stored
        // fields are visible before applyCanonicalControls reads them back.
        if (!wasStatementClass && clazz.reifiesStatements()
                && clazz.canonical().keyFields().isEmpty()) {
            StatementCanonicalDefaults.replaceWithSuggestion(clazz);
            rebuildCanonicalControls();
        }

        // The object's type QID is written ONLY when that is the chosen mode, and
        // cleared otherwise. Writing it unconditionally is how it came to sit beside a
        // vocabulary doing nothing: both were set, and only one reached the query.
        clazz.instanceMapping().sourceQid(
                INSTANCES_OF.equals(selectedText(objectBoundMode))
                        ? RuleNode.cleanQid(valueTypeField.getText())
                        : "");

        applyCanonicalControls();
        refreshDerived();
        afterChange.accept(null);
    }

    private void showRenameError(String message) {
        JOptionPane.showMessageDialog(this, message, "Cannot rename class",
                JOptionPane.WARNING_MESSAGE);
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

    private void refreshValueDomainChoices(String selected) {
        valueDomainBox.removeAllItems();
        valueDomainBox.addItem(NO_VALUE_DOMAIN);
        boolean found = false;
        if (projectModel != null) for (var selection : projectModel.selections()) {
            if (selection instanceof wikidata.explore.model.VocabularySelection) {
                valueDomainBox.addItem(selection.name());
                found |= selection.name().equals(selected);
            }
        }
        // An unresolved declaration is a validation problem, not permission for an
        // editor merely being opened to erase it. Keep it visible and selected until
        // the vocabulary is restored or the user explicitly chooses "none".
        if (selected != null && !selected.isBlank() && !found) {
            valueDomainBox.addItem(selected);
        }
        valueDomainBox.setSelectedItem(selected == null || selected.isBlank()
                ? NO_VALUE_DOMAIN : selected);
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
        primaryListFieldBox.removeAllItems();
        primaryListFieldBox.addItem(INFER_PRIMARY_LIST);

        if (clazz == null) {
            keyFieldsPanel.revalidate();
            keyFieldsPanel.repaint();
            return;
        }

        CanonicalSpec canonical = clazz.canonical();
        duplicatePolicyBox.setSelectedItem(canonical.duplicatePolicy());

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

        // The canonical-list candidates are the multi-valued ENTITY qualifiers — the
        // shared-award recipient list. Listing them is what makes a second one visible
        // instead of being silently outranked by field order.
        for (GeneratedFieldModel field : clazz.fields()) {
            if (StatementFieldSemantics.isRuntimeStatementField(field)
                    && field.mapping().isQualifier()
                    && field.type() == datasource.schema.FieldType.ENTITY
                    && field.cardinality() != null
                    && field.cardinality().isCollection()) {
                primaryListFieldBox.addItem(field.name());
            }
        }
        primaryListFieldBox.setSelectedItem(
                canonical.primaryListField().isBlank()
                        ? INFER_PRIMARY_LIST : canonical.primaryListField());

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
        CanonicalSpec canonical = clazz.canonical();

        canonical.duplicatePolicy(
                (CanonicalSpec.DuplicatePolicy) duplicatePolicyBox.getSelectedItem());

        String primaryList = selectedText(primaryListFieldBox);
        canonical.primaryListField(
                INFER_PRIMARY_LIST.equals(primaryList) ? "" : primaryList);

        canonical.keyFields().clear();

        for (Map.Entry<String, JCheckBox> entry
                : keyFieldBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                canonical.keyFields().add(entry.getKey());
            }
        }

        String displayField =
                selectedText(displayNameFieldBox);

        boolean preserveUneditableTemplate = displayField.isBlank()
                && canonical.displayNameMode()
                == CanonicalSpec.DisplayNameMode.TEMPLATE
                && !canonical.displayNameTemplate().isBlank();
        // This compact statement editor exposes FIELD selection, not the general
        // class editor's TEMPLATE control. Merely visiting and leaving a statement
        // class must therefore preserve a template it cannot display or edit.
        if (!preserveUneditableTemplate) {
            if (displayField.isBlank()) {
                canonical.displayNameMode(CanonicalSpec.DisplayNameMode.LABEL);
                canonical.displayNameField("");
            } else {
                canonical.displayNameMode(CanonicalSpec.DisplayNameMode.FIELD);
                canonical.displayNameField(displayField);
            }
            canonical.displayNameTemplate("");
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

        Reification reification = null;
        if (projectModel != null) {
            try {
                var compiled = wikidata.explore.compiled.ProjectModelCompiler
                        .compile(projectModel);
                reification = ModelStatementReifications.deriveOne(
                        compiled.findClass(clazz.className()).orElse(null), compiled);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // An editor routinely holds an incomplete draft. Runtime derivation
                // starts only after that draft passes the same compiler as generation.
            }
        }

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
            refreshGraphPattern();
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
            if (qualifier.kind() == QualifierLoadConfig.Kind.DATE) {
                qualifiers.append(" (date)");
            } else if (qualifier.kind() == QualifierLoadConfig.Kind.YEAR) {
                // Distinguished, because the difference is what the load keeps.
                qualifiers.append(" (year)");
            }

            qualifiers.append('\n');
        }

        qualifiersArea.setText(
                qualifiers.length() == 0
                        ? " (no qualifier fields yet)"
                        : qualifiers.toString());
        refreshGraphPattern();
    }

    private void refreshGraphPattern() {
        // Read the CHOICE, not the stored policy: this row sits directly under the
        // combo and must not contradict what the reader has just selected while the
        // edit is unapplied.
        if (clazz == null || graphExpansionBox.getSelectedItem()
                != GraphExpansionPolicy.CURATED) {
            graphPatternValue.setText("Disabled — statements are still generated normally.");
            return;
        }
        GraphExpansionPattern pattern = WikidataGraphDiscoveryState
                .structuralPattern(projectModel, clazz.className());
        if (pattern == null) {
            graphPatternValue.setText("⚠ Unavailable — requires direct subject discovery, "
                    + "target seeds and an unrestricted entity value field.");
            return;
        }
        GeneratedClassModel targetClass = projectModel.findClass(pattern.targetNodeClass());
        int seeds = targetClass == null ? 0 : targetClass.seedQids().size();
        graphPatternValue.setText("<html>" + pattern.sourceNodeClass()
                + " &mdash; " + pattern.relation().relationId() + " / "
                + pattern.statementClass() + " &rarr; " + pattern.targetNodeClass()
                + "<br>Initial " + pattern.targetNodeClass() + " nodes: " + seeds
                + " · new " + pattern.targetNodeClass()
                + " values become a curated frontier.</html>");
    }

    void identityChangeConfirmation(
            BiPredicate<List<String>, List<String>> confirmation) {
        identityChangeConfirmation = confirmation == null
                ? this::confirmIdentityChange : confirmation;
    }

    void rederiveIdentity() {
        if (clazz == null) {
            return;
        }

        List<String> current = List.copyOf(clazz.canonical().keyFields());
        List<String> proposed = StatementCanonicalDefaults.suggest(clazz);
        if (current.equals(proposed)) {
            JOptionPane.showMessageDialog(this,
                    "Identity fields already match the derived proposal:\n"
                            + displayIdentityFields(proposed),
                    "Re-derive identity", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!identityChangeConfirmation.test(current, proposed)) {
            return;
        }

        // Identity is independent of display and duplicate handling. Re-deriving
        // the grain must not reset a custom display template or MERGE_RECORDS — the
        // old implementation replaced the whole CanonicalSpec despite this button
        // promising only an identity change.
        StatementCanonicalDefaults.replaceKeyWithSuggestion(clazz);
        rebuildCanonicalControls();
        refreshDerived();
        afterChange.accept(null);
    }

    private boolean confirmIdentityChange(
            List<String> current, List<String> proposed) {
        String message = "Replace the identity fields for " + clazz.className() + "?\n\n"
                + "Current:  " + displayIdentityFields(current) + "\n"
                + "Proposed: " + displayIdentityFields(proposed) + "\n\n"
                + "This changes which statement records are considered duplicates.\n"
                + "Display-name and duplicate-merge settings will not change.";
        return JOptionPane.showConfirmDialog(this, message, "Re-derive identity",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                == JOptionPane.OK_OPTION;
    }

    private static String displayIdentityFields(List<String> fields) {
        return fields == null || fields.isEmpty()
                ? "(no fields — surrogate identity)" : String.join(" + ", fields);
    }

    private void clear() {
        titleLabel.setText("Statement class");
        classNameField.setText("");
        reifyFromBox.removeAllItems();
        statementPropField.setText("P1411");
        valueTypeField.setText("");
        graphExpansionBox.setSelectedItem(GraphExpansionPolicy.NONE);
        graphPatternValue.setText(" ");
        keyFieldsPanel.removeAll();
        keyFieldBoxes.clear();
        duplicatePolicyBox.setSelectedItem(CanonicalSpec.DuplicatePolicy.KEEP_ONE);
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
                new JLabel("<html>Each instance is one statement — a <b>subject</b>, a "
                        + "<b>property</b>, and an <b>object</b> — with its qualifiers "
                        + "said about that statement. Subject and object are named by a "
                        + "class, which is a placeholder: unnamed it is served as a "
                        + "reference (identity and label), and it is specialized by "
                        + "evidence rather than asserted here.</html>");
        explanation.setFont(
                explanation.getFont()
                           .deriveFont(Font.ITALIC));
        GridBagUtils.wideRow(form, row++, explanation);

        GridBagUtils.labeledRow(form, row++,
            "Class name:",
            classNameField);

        JPanel triple = new JPanel(new GridBagLayout());
        triple.setBorder(BorderFactory.createTitledBorder(
                "Statement triple — subject · property · object"));
        GridBagConstraints tc = new GridBagConstraints();
        tc.insets = new Insets(3, 4, 3, 4);
        GridBagUtils.labeledRow(triple, tc, 0, "Subject:", subjectValue);
        subjectBoundValue.setToolTipText(
                "QIDs, separated by spaces or commas. For \"Instances of\", one QID.");
        GridBagUtils.labeledRow(triple, tc, 1, "Subject entities:",
                boundRow(subjectBoundMode, subjectBoundValue));
        GridBagUtils.labeledRow(triple, tc, 2, "Property:", statementPropField);
        GridBagUtils.labeledRow(triple, tc, 3, "Object:", objectValue);
        GridBagUtils.labeledRow(triple, tc, 4, "Object entities:",
                boundRow(objectBoundMode, objectValueHolder));
        GridBagUtils.wideRow(form, row++, triple);

        objectValueHolder.add(new JPanel(), "none");
        objectValueHolder.add(valueDomainBox, "vocabulary");
        objectValueHolder.add(valueTypeField, "type");

        subjectBoundMode.addActionListener(e -> refreshBoundControls());
        objectBoundMode.addActionListener(e -> refreshBoundControls());

        reifyFromBox.setToolTipText(
                "Optional: the already-extracted class whose statements are read, "
                        + "outgoing from its members. Leave blank to discover subjects "
                        + "incoming from the property instead — which then requires the "
                        + "objects to be bounded, since they become the starting set.");
        GridBagUtils.labeledRow(form, row++,
            "Subject population:",
            reifyFromBox);







        JPanel graphDiscovery = new JPanel(new GridBagLayout());
        graphDiscovery.setBorder(BorderFactory.createTitledBorder("Graph discovery"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 4, 3, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        graphExpansionBox.setToolTipText("Whether this statement relation exposes "
                + "newly reached value entities as an expandable graph frontier.");
        graphExpansionBox.addActionListener(event -> refreshGraphPattern());
        GridBagUtils.labeledRow(graphDiscovery, gc, 0,
                "Expansion policy:", graphExpansionBox);
        GridBagUtils.labeledRow(graphDiscovery, gc, 1,
                "Resolved pattern:", graphPatternValue);
        GridBagUtils.wideRow(form, row++, graphDiscovery);

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
            "Same record when:",
            keyFieldsPanel);

        duplicatePolicyBox.setToolTipText("What happens when key fields identify "
                + "several copies: keep the preferred complete copy, or preserve "
                + "its scalar values while unioning collection values from every copy.");
        GridBagUtils.labeledRow(canonical, cc, 1,
            "When duplicates occur:", duplicatePolicyBox);

        displayNameFieldBox.setToolTipText(
                "Single field used as the record's display name.");
        GridBagUtils.labeledRow(canonical, cc, 2,
            "Display-name field:",
            displayNameFieldBox);

        primaryListFieldBox.setToolTipText("<html>Which multi-valued entity qualifier "
                + "marks the CANONICAL copy of a shared statement (#92).<br>"
                + "Wikidata records a shared award on every recipient, so the same "
                + "nomination arrives once per endpoint.<br>The copy carrying the full "
                + "recipient list is the complete one; the others are denormalized "
                + "duplicates and are dropped.<br><b>" + INFER_PRIMARY_LIST + "</b> takes "
                + "the first such qualifier — correct with one candidate, decided by "
                + "field order with two.</html>");
        GridBagUtils.labeledRow(canonical, cc, 3,
            "Canonical list:",
            primaryListFieldBox);

        JButton rederive =
                new JButton("Re-derive identity");
        rederive.setToolTipText(
                "Infer the key again from the current scalar "
                        + "AUTO-produced statement fields.");
        rederive.addActionListener(
                event -> rederiveIdentity());

        GridBagUtils.wideRow(canonical, 4, rederive);
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
