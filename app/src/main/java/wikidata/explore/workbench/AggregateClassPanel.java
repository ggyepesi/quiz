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
    private final JComboBox<String> membersField = new JComboBox<>();
    // An aggregate class has a name, an alias and a base like any other class, and
    // this panel showed none of them — so an aggregate could not be renamed at all:
    // RenameClass is used by the Source, Statement and Owned panels and by nothing
    // else. Nothing in the model or the validator restricts those by kind.
    private final ClassHeaderEditor header;

    /**
     * The fields this class inherits from the class it groups — which ARE its key.
     *
     * <p>Ordered, because the identifier joins their values in order. It was a list of
     * ⟨this field ← that field⟩ pairs over fields this class already had, so the same
     * fact was authored twice: you added a field by hand and then paired it, and the
     * identity editor showed the result as a key you could edit separately. An
     * aggregate does not invent fields — it takes them from the class it groups, and
     * inheriting one is what creates it.
     */
    private final OrderedChoiceList<String> inherited = new OrderedChoiceList<>(true);
    // A template, asked the way every kind asks it. This was a row of field
    // checkboxes composed INTO a template and read back out of one by substring: it
    // could only ever produce "{a} — {b}", so a template written with any other
    // separator, or with a word in it, was rewritten the next time the class was
    // applied — and the mode was then decided by whether the result came out blank,
    // which is a fact derived from something that merely agrees with it.
    private final DisplayNameEditor displayNameEditor = new DisplayNameEditor();
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
        membersField.setToolTipText(
                "List-valued ENTITY fields on this class that hold the selected source class.");
        GridBagUtils.labeledRow(form, c, 4, "Members field:", membersField);
        // "Key field", not "grouped from": these pairs ARE the key — one instance per
        // distinct combination of their values — and naming them after the mechanism
        // left the reader to work out that the identity list above was the same fact.
        inherited.title("Key fields — inherited from the class this one groups");
        inherited.setToolTipText(
                "One instance per distinct combination of these values. Inheriting a "
                        + "field creates it on this class, copying the source field's "
                        + "type; everything NOT inherited is reached through the "
                        + "grouped records.");
        inherited.onChange(() -> {
            if (clazz == null) return;
            applyEdits();
            edit(clazz);
        });
        GridBagUtils.wideRow(form, 5, inherited);
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
        header.applyEdits();
        AggregateClassSource spec = new AggregateClassSource(
                selection(sourceClass), selection(membersField));
        // The list's CONTENTS, not its selection. Reading the selection made clicking
        // a row to look at it an edit that dropped every other pair.
        GeneratedClassModel grouped = project.findClass(selection(sourceClass));
        for (String field : inherited.chosen()) {
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
        if (grouped == null || clazz.fields().stream()
                .anyMatch(field -> field != null && name.equals(field.name()))) {
            return;
        }
        GeneratedFieldModel source = grouped.effectiveFields(project).stream()
                .filter(field -> field != null && name.equals(field.name()))
                .findFirst().orElse(null);
        if (source == null) return;
        GeneratedFieldModel field =
                clazz.addField(name, source.type(), source.cardinality());
        field.entityClassName(source.entityClassName());
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
        inherited.show(new java.util.ArrayList<>(already),
                new java.util.ArrayList<>(offered));
    }

    private static String selection(JComboBox<?> box) {
        Object value = box.getSelectedItem();
        return value == null ? "" : value.toString().trim();
    }
}
