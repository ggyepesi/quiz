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
    private final DefaultListModel<KeyChoice> keyChoices = new DefaultListModel<>();
    private final JList<KeyChoice> keys = new JList<>(keyChoices);
    private final JComboBox<AggregateClassSource.MissingKeyPolicy> missingKeyPolicy =
            new JComboBox<>(AggregateClassSource.MissingKeyPolicy.values());
    private final JPanel displayFieldsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    private final LinkedHashMap<String, JCheckBox> displayFields = new LinkedHashMap<>();
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
        keys.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        keys.setVisibleRowCount(5);
        keys.setToolTipText(
                "Select compatible aggregate/source field pairs that define one group.");
        JScrollPane keyScroll = new JScrollPane(keys);
        keyScroll.setBorder(BorderFactory.createTitledBorder("Group by (target ← source)"));
        GridBagUtils.wideRow(form, 2, keyScroll);
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
    }

    void applyEdits() {
        if (clazz == null) return;
        AggregateClassSource spec = new AggregateClassSource(
                selection(sourceClass), selection(membersField));
        for (KeyChoice choice : keys.getSelectedValuesList()) {
            spec.keys().add(new AggregateClassSource.Key(
                    choice.targetField(), choice.sourceField()));
        }
        spec.missingKeyPolicy((AggregateClassSource.MissingKeyPolicy)
                missingKeyPolicy.getSelectedItem());
        clazz.classKind(ClassKind.AGGREGATE);
        clazz.aggregateSource(spec);
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
        if (selected != null) selected.keys().forEach(key ->
                choices.add(new KeyChoice(key.targetField(), key.sourceField())));
        keyChoices.clear();
        choices.forEach(keyChoices::addElement);
        if (selected != null) {
            Set<KeyChoice> wanted = selected.keys().stream()
                    .map(key -> new KeyChoice(key.targetField(), key.sourceField()))
                    .collect(java.util.stream.Collectors.toSet());
            keys.setSelectedIndices(IntStream.range(0, keyChoices.size())
                    .filter(i -> wanted.contains(keyChoices.get(i))).toArray());
        }
    }

    private void refreshDisplayChoices(GeneratedClassModel value) {
        displayFields.clear();
        displayFieldsPanel.removeAll();
        if (value == null) return;
        String template = value.canonical().displayNameTemplate();
        for (var field : value.fields()) {
            if (field.cardinality() == FieldCardinality.COLLECTION) continue;
            JCheckBox box = new JCheckBox(field.name(),
                    template.contains("{" + field.name() + "}"));
            box.setToolTipText("Include " + field.name() + " in the aggregate title");
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
