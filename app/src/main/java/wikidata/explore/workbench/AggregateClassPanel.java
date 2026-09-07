package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Editor for the provider-neutral, offline aggregate-class recipe. */
final class AggregateClassPanel extends JPanel {
    private final GeneratedProjectModel project;
    private final JComboBox<String> sourceClass = new JComboBox<>();
    /**
     * The field that receives the grouped records — the same form as the key, unordered.
     *
     * <p>"Members field" named the mechanism; these are the aggregated fields, the other
     * half of what this class takes from the class it groups: its FIELDS become the key,
     * its RECORDS go here. One entry, because the model unions whole records into one
     * field — collecting a single field's values across a group would be a different
     * production, and it does not exist.
     */
    private final OrderedChoiceList<String> aggregated = new OrderedChoiceList<>(false);
    // An aggregate class has a name, an alias and a base like any other class, and
    // this panel showed none of them — so an aggregate could not be renamed at all:
    // RenameClass is used by the Source, Statement and Owned panels and by nothing
    // else. Nothing in the model or the validator restricts those by kind.
    private final ClassHeaderEditor header;

    /**
     * The key: the fields this class takes from the class it groups.
     *
     * <p>One name, because there is one thing. "Key fields" and "inherited fields" were
     * two names for it — a field is taken from the grouped class BECAUSE it identifies
     * the group, and identifies the group BECAUSE it is taken. Ordered, since the
     * identifier joins their values in that order.
     *
     * <p>It was a list of ⟨this field ← that field⟩ pairs over fields this class
     * already had, so the same fact was authored twice: you added a field by hand and
     * then paired it, and the identity editor showed the result as a key you could edit
     * separately.
     */
    private final OrderedChoiceList<String> keyFields = new OrderedChoiceList<>(true);
    // A template, asked the way every kind asks it. This was a row of field
    // checkboxes composed INTO a template and read back out of one by substring: it
    // could only ever produce "{a} — {b}", so a template written with any other
    // separator, or with a word in it, was rewritten the next time the class was
    // applied — and the mode was then decided by whether the result came out blank,
    // which is a fact derived from something that merely agrees with it.
    private final DisplayNameEditor displayNameEditor = new DisplayNameEditor();
    // Identity, explained the way every construct explains it. The key list below is
    // its one author because selecting a key also creates and shapes the field from the
    // grouped class; the shared identity view therefore shows that key as fixed while
    // retaining the shared reduction and missing-key controls.
    private final ClassIdentityEditor identityEditor = new ClassIdentityEditor();
    private GeneratedClassModel clazz;
    private boolean refreshing;
    private Consumer<Void> afterChange = ignored -> {};

    AggregateClassPanel(GeneratedProjectModel project) {
        super(new BorderLayout());
        this.project = project;
        this.header = new ClassHeaderEditor(() -> project);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        // The same order as every other kind, minus the triple it does not have:
        // what identifies an instance, then what names it, then this kind's own rows.
        GridBagUtils.wideRow(form, 0, header);
        GridBagUtils.wideRow(form, 1, identityEditor);
        GridBagUtils.wideRow(form, 2, displayNameEditor);
        GridBagUtils.labeledRow(form, c, 3, "From class:", sourceClass);
        aggregated.title("Aggregated fields");
        aggregated.setToolTipText(
                "The field that receives the grouped records themselves. Everything the "
                        + "grouped class has that the key does not take is reached "
                        + "through them.");
        aggregated.onChange(() -> {
            if (clazz == null) return;
            applyEdits();
            edit(clazz);
        });
        GridBagUtils.wideRow(form, 4, aggregated);
        // "Key field", not "grouped from": these pairs ARE the key — one instance per
        // distinct combination of their values — and naming them after the mechanism
        // left the reader to work out that the identity list above was the same fact.
        keyFields.title("Key fields");
        keyFields.setToolTipText(
                "The fields this class takes from the class it groups: one instance per "
                        + "distinct combination of their values. Taking a field creates "
                        + "it here, copying the source field's type; everything not "
                        + "taken is reached through the grouped records.");
        keyFields.onChange(() -> {
            if (clazz == null) return;
            applyEdits();
            edit(clazz);
        });
        GridBagUtils.wideRow(form, 5, keyFields);
        GridBagUtils.wideRow(form, 6, new JLabel(
                "Choices come from compatible fields on this class and its source class."));
        add(new JScrollPane(form), BorderLayout.CENTER);
        sourceClass.addActionListener(e -> {
            if (!refreshing) refreshChoices(null);
        });
    }

    void afterChange(Consumer<Void> value) {
        afterChange = value == null ? ignored -> {} : value;
    }

    void edit(GeneratedClassModel value) {
        header.show(value);
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
        displayNameEditor.show(value);
        identityEditor.showFixedKey(value);
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
        identityEditor.showFixedKey(clazz);
    }

    void applyEdits() {
        if (clazz == null) return;
        header.applyEdits();
        GeneratedClassModel groupedClass = project.findClass(selection(sourceClass));
        String members = aggregated.chosen().isEmpty()
                ? "" : aggregated.chosen().get(0);
        if (!members.isBlank()) receiveRecords(groupedClass, members);
        AggregateClassSource spec = new AggregateClassSource(
                selection(sourceClass), members);
        // The list's CONTENTS, not its selection. Reading the selection made clicking
        // a row to look at it an edit that dropped every other pair.
        GeneratedClassModel grouped = groupedClass;
        for (String field : keyFields.chosen()) {
            inherit(grouped, field);
            spec.keys().add(new AggregateClassSource.Key(field, field));
        }
        clazz.classKind(ClassKind.AGGREGATE);
        clazz.aggregateSource(spec);
        syncKeyWithPairs(spec);
        // After the kind is assigned: the editor asks the policy what LABEL means for
        // this kind, and the answer differs by kind.
        displayNameEditor.applyEdits();
        afterChange.accept(null);
    }




    /**
     * Gives this class the field it inherits, shaped by the one it comes from.
     *
     * <p>A grouped field holds the source field's values, so it is that field's type,
     * target class and cardinality. Creating it here is what lets Add field go from an
     * aggregate: there is nothing to add by hand, because every field it can have comes
     * from the class it groups.
     */
    private void inherit(GeneratedClassModel grouped, String name) {
        if (grouped == null) return;
        GeneratedFieldModel source = grouped.effectiveFields(project).stream()
                .filter(field -> field != null && name.equals(field.name()))
                .findFirst().orElse(null);
        if (source == null) return;
        GeneratedFieldModel field = clazz.fields().stream()
                .filter(candidate -> candidate != null && name.equals(candidate.name()))
                .findFirst()
                .orElseGet(() -> clazz.addField(
                        name, source.type(), source.cardinality()));
        field.type(source.type());
        field.cardinality(source.cardinality());
        field.entityClassName(source.entityClassName());
    }

    /**
     * Gives this class the field the grouped records go into, if it has none.
     *
     * <p>Named after the class it holds, which is what every such field is already
     * called — NobelPrize.laureatesWithMotivation holds LaureatesWithMotivation. It has
     * to be created here: an aggregate invents no fields, so Add field is refused on
     * one, and this is the only way the records get somewhere to go.
     */
    private void receiveRecords(GeneratedClassModel grouped, String name) {
        if (grouped == null) return;
        GeneratedFieldModel field = clazz.fields().stream()
                .filter(candidate -> candidate != null && name.equals(candidate.name()))
                .findFirst()
                .orElseGet(() -> clazz.addField(
                        name, FieldType.ENTITY, FieldCardinality.COLLECTION));
        field.type(FieldType.ENTITY);
        field.cardinality(FieldCardinality.COLLECTION);
        field.entityClassName(grouped.className());
    }

    /** What a field holding this class's records is called: the class, decapitalised. */
    private static String recordsFieldName(GeneratedClassModel grouped) {
        String name = grouped.className();
        return name.isEmpty() ? "" : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private void refreshChoices(AggregateClassSource selected) {
        if (clazz == null) return;
        GeneratedClassModel source = project.findClass(
                selection(sourceClass));

        String selectedMember = selected == null ? "" : selected.membersField();
        LinkedHashSet<String> holders = new LinkedHashSet<>();
        for (var field : clazz.fields()) {
            if (field.type() == FieldType.ENTITY
                    && field.cardinality() == FieldCardinality.COLLECTION
                    && (source == null || source.className().equals(field.entityClassName()))) {
                holders.add(field.name());
            }
        }
        // The field this class does not have yet is offered by the name it would take,
        // and created when it is chosen — an aggregate invents no fields by hand.
        if (source != null) holders.add(recordsFieldName(source));
        java.util.List<String> chosenMember = selectedMember.isBlank()
                ? java.util.List.of() : java.util.List.of(selectedMember);
        aggregated.show(chosenMember, new java.util.ArrayList<>(holders));

        // What can be inherited: the grouped class's own scalar fields. A collection
        // cannot be a key — one instance per combination of values needs one value.
        LinkedHashSet<String> offered = new LinkedHashSet<>();
        if (source != null) {
            for (var field : source.effectiveFields(project)) {
                if (field == null || field.cardinality() == FieldCardinality.COLLECTION) {
                    continue;
                }
                offered.add(field.name());
            }
        }
        LinkedHashSet<String> already = new LinkedHashSet<>();
        if (selected != null) selected.keys().forEach(key -> {
            // A pair authored with different names on the two sides still reads as the
            // source field it takes its values from, which is what is being chosen.
            already.add(key.sourceField());
            offered.add(key.sourceField());
        });
        keyFields.show(new java.util.ArrayList<>(already),
                new java.util.ArrayList<>(offered));
    }

    private static String selection(JComboBox<?> box) {
        Object value = box.getSelectedItem();
        return value == null ? "" : value.toString().trim();
    }
}
