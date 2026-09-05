package wikidata.explore.workbench;

import datasource.schema.FieldType;

import objectview.utils.swing.GridBagUtils;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphExpansionPolicy;
import wikidata.WikidataIds;
import wikidata.explore.model.StatementIdentity;
import wikidata.explore.model.EntityBound;
import wikidata.explore.generation.WikidataGraphDiscoveryState;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementDisplayDefaults;
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

    private static final String ANY = "Anything";
    private static final String THESE_ENTITIES = "These entities";
    private static final String INSTANCES_OF = "Instances of";
    private static final String A_VOCABULARY = "A vocabulary";

    private final JLabel titleLabel = new JLabel("Statement class");
    /** The "no declaration — infer it" choice, shown instead of a blank row. */
    private static final String INFER_PRIMARY_LIST = "(infer)";

    private final ClassHeaderEditor header =
            new ClassHeaderEditor(() -> projectModel);
    // Subject, property and object in one component, because that is one construct.
    // The subject's POPULATION was the one leg with a control outside the box the box
    // was named after: naming the class whose members are the subjects is a way of
    // bounding the subject end, not a separate question asked beside it.
    private final TripleEditor triple =
            new TripleEditor("Statement triple — subject · property · object");
    private static final String NO_VALUE_DOMAIN = "(none)";
    private final JComboBox<String> valueDomainBox = new JComboBox<>();
    // One control per end, so the alternatives cannot be configured together. They used
    // to be separate rows that looked combinable while the loader silently kept one.
    private final JTextField valueTypeField = new JTextField(10);
    private final JComboBox<GraphExpansionPolicy> graphExpansionBox =
            new JComboBox<>(GraphExpansionPolicy.values());
    private final JLabel graphPatternValue = new JLabel(" ");

    // One editor for identity and same-key handling, shared with every other construct.
    private final ClassIdentityEditor identityEditor = new ClassIdentityEditor();
    private final JPanel keyFieldsPanel =
            new JPanel(new GridBagLayout());
    private final Map<String, JCheckBox> keyFieldBoxes =
            new LinkedHashMap<>();
    private final JComboBox<CanonicalSpec.DuplicatePolicy> duplicatePolicyBox =
            new JComboBox<>(CanonicalSpec.DuplicatePolicy.values());

    // Mode, field and template. This was a field box alone, so a class named by a
    // template had nothing to show it in and the template was put into the box as an
    // uneditable string.
    private final DisplayNameEditor displayNameEditor = new DisplayNameEditor();
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
        header.show(clazz);

        StatementClassSource source =
                clazz.statementSource();

        refreshValueDomainChoices(source == null ? "" : source.valueSelectionName());

        // The value-type filter remains part of the statement class's
        // extraction mapping: it constrains the ps: value by P31.
        valueTypeField.setText(
                clazz.instanceMapping().sourceQid());
        graphExpansionBox.setSelectedItem(source == null
                ? GraphExpansionPolicy.NONE : source.graphExpansionPolicy());

        identityEditor.show(clazz);
        refreshTriple();
        rebuildCanonicalControls();
        refreshDerived();
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
        // Every authored route, not only the declared-subject one. A class settling its
        // subject through a participants collection was reported as unconfigured, which
        // told the reader a domain that generates could not.
        StatementFieldSemantics.SubjectDestination subject =
                StatementFieldSemantics.subjectDestination(clazz);
        // The validator's own predicate, not a second reading of the same idea.
        boolean projectionRequired =
                projectModel != null && projectModel.acquiresInstances();
        triple.subjectDestination(subject.fieldName(),
                targetClassOf(subject.fieldName()), valueKindOf(subject.fieldName()),
                subject.route().phrase(), projectionRequired);
        String objectField = StatementFieldSemantics.statementValueFieldName(clazz);
        triple.objectDestination(objectField, targetClassOf(objectField),
                valueKindOf(objectField), "the value the statement points at",
                projectionRequired);

        java.util.List<String> vocabularies = projectModel == null ? java.util.List.of()
                : projectModel.selections().stream()
                        .filter(selection -> selection instanceof wikidata.explore.model.VocabularySelection)
                        .map(wikidata.explore.model.Selection::name)
                        .toList();
        triple.vocabularies(vocabularies);

        StatementClassSource source = clazz == null ? null : clazz.statementSource();
        triple.subjectPopulation(subjectPopulationCandidates(),
                source == null ? "" : source.sourceClassName());
        triple.show(source == null ? "" : source.propertyPid(),
                source == null ? null : source.subjectBound(),
                source == null ? null : source.objectBound());
    }

    /** The placeholder class a leg's field is typed as, or blank when it names none. */
    /** The datatype the receiving field declares — entity, date, string, and so on. */
    private String valueKindOf(String fieldName) {
        if (clazz == null || fieldName == null || fieldName.isBlank()) return "";
        return clazz.fields().stream()
                .filter(candidate -> candidate != null
                        && fieldName.equals(candidate.name()))
                .findFirst()
                .map(field -> field.type() == null ? "" : field.type().name())
                .orElse("");
    }

    private String targetClassOf(String fieldName) {
        if (clazz == null || fieldName == null || fieldName.isBlank()) return "";
        return clazz.fields().stream()
                .filter(candidate -> candidate != null
                        && fieldName.equals(candidate.name()))
                .findFirst().map(GeneratedFieldModel::entityClassName).orElse("");
    }

    /** Instances to try the identity configuration against, before applying it. */
    void previewAgainst(java.util.List<canonical.Candidate> candidates) {
        identityEditor.previewAgainst(candidates);
    }

    public void applyEdits() {
        if (clazz == null) {
            return;
        }

        header.applyEdits();

        String sourceClass = triple.subjectPopulation();
        String statementPid = triple.propertyPid();

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
            // Each end reports ONE value, from the same editor. There is no state in
            // which two bounds compete, so nothing here has to clear the loser.
            next.subjectBound(triple.subjectBound());
            next.objectBound(triple.objectBound());

            next.graphExpansionPolicy((GraphExpansionPolicy)
                    graphExpansionBox.getSelectedItem());
            clazz.statementSource(next);
        }


        // The class's own sourceQid is no longer the object's type filter: that moved
        // into objectBound, where a bound belongs. It meant "this class's membership
        // type" everywhere else, and a statement class quietly reusing it for its object
        // was one field with two meanings.

        applyCanonicalControls();
        refreshDerived();
        afterChange.accept(null);
    }

    /** The classes whose members could be this triple's subjects — never this one. */
    private java.util.List<String> subjectPopulationCandidates() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String name : sourceClassCandidates.get()) {
            if (name == null || name.isBlank()) continue;
            if (clazz != null && name.equals(clazz.className())) continue;
            names.add(name);
        }
        return names;
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

        displayNameEditor.show(clazz);
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

        // The key is NOT written here. It belongs to the identity editor, which keeps
        // it as an ordered list. Rebuilding it from checkbox order silently rewrote any
        // key authored in a different order — and identity joins a key's values IN
        // order, so that changed every instance's identifier. It went unnoticed because
        // all three shipped models happen to have been authored in field order.

        clazz.canonical(canonical);

        // All three modes are editable here now, so there is nothing to preserve
        // around. The rule "keep whatever this editor could not show" existed because
        // it could not show a template; it wrote the spec last, so it goes last.
        displayNameEditor.applyEdits();
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

    private static String displayIdentityFields(List<String> fields) {
        return fields == null || fields.isEmpty()
                ? "(no fields — surrogate identity)" : String.join(" + ", fields);
    }

    private void clear() {
        titleLabel.setText("Statement class");
        header.show(null);
        triple.clear();
        valueTypeField.setText("");
        graphExpansionBox.setSelectedItem(GraphExpansionPolicy.NONE);
        graphPatternValue.setText(" ");
        keyFieldsPanel.removeAll();
        keyFieldBoxes.clear();
        duplicatePolicyBox.setSelectedItem(CanonicalSpec.DuplicatePolicy.KEEP_ONE);
        displayNameEditor.show(null);
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

        GridBagUtils.wideRow(form, row++, header);

        GridBagUtils.wideRow(form, row++, triple);



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
        // Added last, below. Graph discovery says how far generation walks OUT from
        // this class; it is not part of saying what the statement IS, and sitting
        // between the triple and the identity it separated two halves of one thought.

        JPanel canonical =
                new JPanel(new GridBagLayout());
        // No title: the boxes inside are titled "Identity" and "When the same key
        // occurs", and a third border reading "Canonical identity" around them was a
        // heading for its own contents.
        canonical.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        GridBagConstraints cc =
                new GridBagConstraints();
        cc.insets = new Insets(3, 4, 3, 4);
        cc.anchor = GridBagConstraints.NORTHWEST;
        cc.fill = GridBagConstraints.HORIZONTAL;

        // The shared editor replaces the checkbox grid: an ordered key, and a reducer
        // per field, asked the same way on every construct.
        GridBagUtils.wideRow(canonical, 0, identityEditor);

        // Superseded, and shown saying so rather than left looking live. It is a
        // class-wide policy, so it could never express "union the laureates while
        // requiring the category to agree" — the per-field rules above do, and the
        // reify path now reads those. Nothing in a run consults this any more. It stays
        // visible only because the shipped models still carry it, and it goes when they
        // are regenerated.
        duplicatePolicyBox.setEnabled(false);
        duplicatePolicyBox.setToolTipText(
                "Superseded by the per-field rules above. It no longer affects a run.");
        GridBagUtils.labeledRow(canonical, cc, 1,
            "When duplicates occur (superseded):", duplicatePolicyBox);

        GridBagUtils.wideRow(canonical, 2, displayNameEditor);

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

        // The offer moved into the identity editor, where the key is.

        // No "Re-derive identity" button. It replaced the configured key with a
        // guess swept from the scalar AUTO fields, which is why its purpose was
        // unanswerable: identity is configured in this box, and a button that
        // overwrites that configuration is not a derivation.
        //
        // No "Refresh derived view" either. It called applyEdits() and then a refresh
        // that applyEdits() already ends with — an Apply button wearing an inspection
        // label, which is directive 9 inverted.
        GridBagUtils.wideRow(form, row++, canonical);

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
        GridBagUtils.wideRow(form, row++, graphDiscovery);

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
