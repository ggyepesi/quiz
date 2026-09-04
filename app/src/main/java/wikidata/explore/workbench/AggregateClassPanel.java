package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Editor for the provider-neutral, offline aggregate-class recipe. */
final class AggregateClassPanel extends JPanel {
    private final GeneratedProjectModel project;
    private final JComboBox<String> sourceClass = new JComboBox<>();
    private final JComboBox<String> membersField = new JComboBox<>();
    // The list holds the CONFIGURED pairs; it is not a menu whose selection is the
    // configuration. It was, and one plain click reconfigured the class: a JList in
    // MULTIPLE_INTERVAL_SELECTION replaces its selection, so clicking a row to look at
    // it deselected every other pair, and applying then dropped those pairs and the key
    // components that depended on them. Clicking to inspect must not configure.
    private final DefaultListModel<KeyChoice> keyChoices = new DefaultListModel<>();
    private final JList<KeyChoice> keys = new JList<>(keyChoices);
    private final JComboBox<KeyChoice> addablePair = new JComboBox<>();
    private final JComboBox<AggregateClassSource.MissingKeyPolicy> missingKeyPolicy =
            new JComboBox<>(AggregateClassSource.MissingKeyPolicy.values());
    private final JPanel displayFieldsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final LinkedHashMap<String, JCheckBox> displayFields = new LinkedHashMap<>();
    // Identity, asked the way every construct asks it. The pair list below says which
    // of this class's fields HAVE a source to group from; this says which of them
    // identify an instance, and in what order.
    private final ClassIdentityEditor identityEditor = new ClassIdentityEditor();
    private GeneratedClassModel clazz;
    private boolean refreshing;
    private Consumer<Void> afterChange = ignored -> {};

    AggregateClassPanel(GeneratedProjectModel project) {
        super(new BorderLayout());
        this.project = project;
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        GridBagUtils.labeledRow(form, c, 0, "From class:", sourceClass);
        membersField.setToolTipText(
                "List-valued ENTITY fields on this class that hold the selected source class.");
        GridBagUtils.labeledRow(form, c, 1, "Members field:", membersField);
        keys.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        keys.setVisibleRowCount(5);
        keys.setToolTipText(
                "Which of this class's fields is grouped from which field of the source "
                        + "class. Selecting a row only chooses what Remove would take.");
        JScrollPane keyScroll = new JScrollPane(keys);
        JPanel grouping = new JPanel(new java.awt.BorderLayout(4, 4));
        grouping.setBorder(BorderFactory.createTitledBorder(
                "Grouped from (this class's field ← source class's field)"));
        grouping.add(keyScroll, java.awt.BorderLayout.CENTER);
        JPanel pairButtons = new JPanel();
        pairButtons.setLayout(new javax.swing.BoxLayout(
                pairButtons, javax.swing.BoxLayout.Y_AXIS));
        JButton addPair = new JButton("Add");
        addPair.addActionListener(event -> addSelectedPair());
        JButton removePair = new JButton("Remove");
        removePair.addActionListener(event -> removeSelectedPair());
        pairButtons.add(addPair);
        pairButtons.add(removePair);
        pairButtons.add(javax.swing.Box.createVerticalStrut(8));
        JLabel addableLabel = new JLabel("Available:");
        addableLabel.setToolTipText(
                "Pairs this class could be grouped from. Add puts the chosen one into "
                        + "the list; Remove takes the selected row out of it.");
        pairButtons.add(addableLabel);
        pairButtons.add(addablePair);
        grouping.add(pairButtons, java.awt.BorderLayout.EAST);
        GridBagUtils.wideRow(form, 2, grouping);
        GridBagUtils.wideRow(form, 7, identityEditor);
        GridBagUtils.labeledRow(form, c, 3, "Missing key:", missingKeyPolicy);
        displayFieldsPanel.setBorder(BorderFactory.createTitledBorder("Display name fields"));
        displayFieldsPanel.setToolTipText(
                "Checked fields are shown in model order, separated by an em dash.");
        GridBagUtils.wideRow(form, 4, displayFieldsPanel);
        GridBagUtils.wideRow(form, 5, new JLabel(
                "Choices come from compatible fields on this class and its source class."));
        JButton apply = new JButton("Apply aggregate class");
        apply.addActionListener(e -> applyEdits());
        GridBagUtils.wideRow(form, 6, apply);
        add(new JScrollPane(form), BorderLayout.CENTER);
        sourceClass.addActionListener(e -> {
            if (!refreshing) refreshChoices(null);
        });
    }

    void afterChange(Consumer<Void> value) {
        afterChange = value == null ? ignored -> {} : value;
    }

    void edit(GeneratedClassModel value) {
        clazz = value;
        refreshing = true;
        sourceClass.removeAllItems();
        sourceClass.addItem("");
        for (GeneratedClassModel candidate : project.classes()) {
            if (candidate != value) sourceClass.addItem(candidate.className());
        }
        AggregateClassSource spec = value == null ? null : value.aggregateSource();
        sourceClass.setSelectedItem(spec == null ? "" : spec.sourceClassName());
        refreshing = false;
        refreshChoices(spec);
        missingKeyPolicy.setSelectedItem(spec == null
                ? AggregateClassSource.MissingKeyPolicy.EXCLUDE : spec.missingKeyPolicy());
        refreshDisplayChoices(value);
        identityEditor.show(value);
    }

    /**
     * Keeps the canonical key to the fields that have a source to group from.
     *
     * <p>The two say different things and neither implies the other: a pair says a field
     * CAN be grouped, the key says it DOES identify. So a pair that is removed takes its
     * key component with it — it has nothing left to group by — while the order of what
     * remains is untouched, because that order is part of the identity and the identity
     * editor owns it.
     */
    private void syncKeyWithPairs(AggregateClassSource spec) {
        java.util.List<String> paired = spec.keys().stream()
                .filter(java.util.Objects::nonNull)
                .map(AggregateClassSource.Key::targetField)
                .filter(field -> !field.isBlank())
                .toList();
        java.util.List<String> key = new java.util.ArrayList<>();
        for (String existing : clazz.canonical().keyFields()) {
            if (paired.contains(existing)) key.add(existing);
        }
        for (String target : paired) {
            if (!key.contains(target)) key.add(target);
        }
        clazz.canonical().keyFields().clear();
        clazz.canonical().keyFields().addAll(key);
        identityEditor.show(clazz);
    }

    void applyEdits() {
        if (clazz == null) return;
        AggregateClassSource spec = new AggregateClassSource(
                selection(sourceClass), selection(membersField));
        // The list's CONTENTS, not its selection. Reading the selection made clicking
        // a row to look at it an edit that dropped every other pair.
        for (int i = 0; i < keyChoices.size(); i++) {
            KeyChoice choice = keyChoices.get(i);
            spec.keys().add(new AggregateClassSource.Key(
                    choice.targetField(), choice.sourceField()));
        }
        spec.missingKeyPolicy((AggregateClassSource.MissingKeyPolicy)
                missingKeyPolicy.getSelectedItem());
        clazz.classKind(ClassKind.AGGREGATE);
        clazz.aggregateSource(spec);
        syncKeyWithPairs(spec);
        String template = displayFields.entrySet().stream()
                .filter(entry -> entry.getValue().isSelected())
                .map(entry -> "{" + entry.getKey() + "}")
                .collect(java.util.stream.Collectors.joining(" — "));
        clazz.canonical().displayNameTemplate(template);
        clazz.canonical().displayNameMode(template.isBlank()
                ? CanonicalSpec.DisplayNameMode.LABEL
                : CanonicalSpec.DisplayNameMode.TEMPLATE);
        afterChange.accept(null);
    }

    /** Adds the chosen pair. An explicit act, so nothing is configured by looking. */
    private void addSelectedPair() {
        Object chosen = addablePair.getSelectedItem();
        if (clazz == null || !(chosen instanceof KeyChoice pair)) return;
        if (!contains(pair)) keyChoices.addElement(pair);
        applyEdits();
        edit(clazz);
    }

    private void removeSelectedPair() {
        int index = keys.getSelectedIndex();
        if (clazz == null || index < 0) return;
        keyChoices.remove(index);
        applyEdits();
        edit(clazz);
    }

    private boolean contains(KeyChoice pair) {
        for (int i = 0; i < keyChoices.size(); i++) {
            if (keyChoices.get(i).equals(pair)) return true;
        }
        return false;
    }

    private void refreshChoices(AggregateClassSource selected) {
        if (clazz == null) return;
        GeneratedClassModel source = project.findClass(
                selection(sourceClass));

        String selectedMember = selected == null
                ? selection(membersField) : selected.membersField();
        membersField.removeAllItems();
        membersField.addItem("");
        for (var field : clazz.fields()) {
            if (field.type() == FieldType.ENTITY
                    && field.cardinality() == FieldCardinality.COLLECTION
                    && (source == null || source.className().equals(field.entityClassName()))) {
                membersField.addItem(field.name());
            }
        }
        membersField.setSelectedItem(selectedMember);
        // An aggregate holds its sources in one of its OWN fields, so that field must be
        // a list of the source class. Choosing a source this class cannot hold left the
        // control empty and said nothing — a dead end that looks like a bug in the
        // editor rather than a fact about the model.
        boolean holdable = membersField.getItemCount() > 1;
        membersField.setToolTipText(holdable
                ? "A list field on this class that holds the source records."
                : source == null
                        ? "Choose the class to group first."
                        : "This class has no list field of " + source.className()
                                + ", so it cannot hold those records. Add one to "
                                + clazz.className() + " first.");

        LinkedHashSet<KeyChoice> choices = new LinkedHashSet<>();
        if (source != null) {
            for (var target : clazz.fields()) {
                if (target.cardinality() == FieldCardinality.COLLECTION) continue;
                for (var input : source.effectiveFields(project)) {
                    if (input.cardinality() == FieldCardinality.COLLECTION) continue;
                    if (target.type() == input.type()) {
                        choices.add(new KeyChoice(target.name(), input.name()));
                    }
                }
            }
        }
        // The list shows what IS configured; the combo offers what could be added. A
        // menu whose selection was the configuration could not tell looking from
        // choosing, so those are now two controls.
        LinkedHashSet<KeyChoice> configured = new LinkedHashSet<>();
        if (selected != null) selected.keys().forEach(key ->
                configured.add(new KeyChoice(key.targetField(), key.sourceField())));
        keyChoices.clear();
        configured.forEach(keyChoices::addElement);

        addablePair.removeAllItems();
        for (KeyChoice choice : choices) {
            if (!configured.contains(choice)) addablePair.addItem(choice);
        }
    }

    private void refreshDisplayChoices(GeneratedClassModel value) {
        displayFields.clear();
        displayFieldsPanel.removeAll();
        if (value == null) return;
        String template = value.canonical().displayNameTemplate();
        for (var field : value.fields()) {
            // A collection is offered. It was skipped, which contradicted the mechanism
            // it feeds: a display TEMPLATE renders whatever field it names, and Nobel's
            // own statement class is titled "{laureates} — {category}" from a collection.
            // Excluding them here meant an aggregate could not be titled by its members —
            // the one thing an aggregate has that its sources do not.
            JCheckBox box = new JCheckBox(field.name(),
                    template.contains("{" + field.name() + "}"));
            box.setToolTipText(field.cardinality() == FieldCardinality.COLLECTION
                    ? "Include every " + field.name() + " value in the aggregate title"
                    : "Include " + field.name() + " in the aggregate title");
            displayFields.put(field.name(), box);
            displayFieldsPanel.add(box);
        }
        displayFieldsPanel.revalidate();
        displayFieldsPanel.repaint();
    }

    private record KeyChoice(String targetField, String sourceField) {
        @Override public String toString() { return targetField + " ← " + sourceField; }
    }

    private static String selection(JComboBox<?> box) {
        Object value = box.getSelectedItem();
        return value == null ? "" : value.toString().trim();
    }
}
