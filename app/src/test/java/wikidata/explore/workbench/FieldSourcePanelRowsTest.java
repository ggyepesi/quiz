package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceType;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reader is shown when configuring one field.
 *
 * <p>A row that cannot apply to the field being configured is not shown at all. This is
 * the test the panel could not have while rows were built from string literals: nothing
 * held the label, so nothing could be hidden and there was no visible set to assert.
 */
class FieldSourcePanelRowsTest {

    @Test void anOrdinaryFieldIsNotOfferedTheRowsOfOtherKinds() {
        FieldSourcePanel panel = editing(field("birthName", FieldType.STRING,
                FieldProductionKind.AUTO, ""));

        List<String> shown = visibleRowLabels(panel);
        assertTrue(shown.contains("Load as:"), shown.toString());
        for (String elsewhere : List.of("Inverse of:", "Match role field:",
                "Match value field:", "Subject field:", "Graph expansion:",
                "Qualifier time:", "Missing qualifier:", "Reify role:")) {
            assertTrue(!shown.contains(elsewhere),
                    elsewhere + " belongs to another kind of field: " + shown);
        }
    }

    @Test void anInverseFieldIsAskedWhichForwardFieldItReads() {
        FieldSourcePanel panel = editing(field("holders", FieldType.ENTITY,
                FieldProductionKind.INVERT, "Person"));

        assertTrue(visibleRowLabels(panel).contains("Inverse of:"),
                "an INVERT field must be asked the one thing it needs");
    }

    /**
     * Expansion follows an edge and then follows it again, so it is offered only where
     * the target is the owner's own class. A cross-class target reaches instances of
     * something else, and treating their seeds as coverage of this edge would be false.
     */
    @Test void aFieldPointingAtItsOwnClassMayDeclareGraphExpansion() {
        GeneratedFieldModel broader =
                field("broader", FieldType.ENTITY, FieldProductionKind.AUTO, "Owner");
        broader.mapping().sourceType(FieldSourceType.SPARQL);
        broader.mapping().propertyPid("P279");

        assertTrue(visibleRowLabels(editing(broader)).contains("Graph expansion:"),
                "the row appears exactly where the traversal rule says it could apply");
    }

    @Test void aFieldPointingAtAnotherClassMayNot() {
        GeneratedFieldModel spouse =
                field("spouse", FieldType.ENTITY, FieldProductionKind.AUTO, "Person");
        spouse.mapping().sourceType(FieldSourceType.SPARQL);
        spouse.mapping().propertyPid("P26");

        assertFalse(visibleRowLabels(editing(spouse)).contains("Graph expansion:"),
                "Person is not the owner's class, so following the edge twice would "
                        + "leave the population this edge is about");
    }

    @Test void aVocabularyTargetIsNotOfferedEntityGraphExpansion() {
        GeneratedFieldModel genre =
                field("genre", FieldType.ENTITY, FieldProductionKind.AUTO, "Genres");
        genre.mapping().sourceType(FieldSourceType.SPARQL);
        genre.mapping().propertyPid("P136");

        FieldSourcePanel panel = new FieldSourcePanel();
        GeneratedProjectModel project = projectContaining(genre, false);
        project.addSelection(new wikidata.explore.model.VocabularySelection("Genres"));
        panel.setProjectModel(project);
        panel.edit(genre);

        assertTrue(!visibleRowLabels(panel).contains("Graph expansion:"),
                "a selection is an entity-shaped target, but not a modeled traversal class");
    }

    @Test void changingTheLiveTypeImmediatelyRevealsDateProjectionRows() {
        FieldSourcePanel panel = editing(field(
                "when", FieldType.STRING, FieldProductionKind.AUTO, ""));
        JComboBox<?> type = comboContaining(panel, FieldType.DATE);

        type.setSelectedItem(FieldType.DATE);
        List<String> shown = visibleRowLabels(panel);
        assertTrue(shown.contains("Subject field:"), shown.toString());
        assertTrue(shown.contains("Match value field:"), shown.toString());
        assertTrue(!shown.contains("Match role field:"), shown.toString());

        type.setSelectedItem(FieldType.STRING);
        shown = visibleRowLabels(panel);
        assertTrue(!shown.contains("Subject field:"), shown.toString());
        assertTrue(!shown.contains("Match value field:"), shown.toString());
    }

    @Test void companionMatchShowsAllThreeMatchingRows() {
        List<String> shown = visibleRowLabels(editing(field(
                "match", FieldType.STRING, FieldProductionKind.COMPANION_MATCH, "")));

        assertTrue(shown.contains("Subject field:"), shown.toString());
        assertTrue(shown.contains("Match value field:"), shown.toString());
        assertTrue(shown.contains("Match role field:"), shown.toString());
    }

    // The qualifier SOURCE is the deliberate exception: it stays visible on a class
    // that cannot use it, so the class/field relationship is explicit. Only the
    // settings OF a qualifier disappear when there is no qualifier to settle.
    @Test void theQualifierSourceStaysVisibleWhileItsSettingsDoNot() {
        List<String> shown = visibleRowLabels(editing(field(
                "birthName", FieldType.STRING, FieldProductionKind.AUTO, "")));

        assertTrue(shown.contains("Qualifier of:"), shown.toString());
        assertTrue(!shown.contains("Reify role:"), shown.toString());
    }

    private static GeneratedFieldModel field(
            String name, FieldType type, FieldProductionKind kind, String target) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        project.addClass(person);
        project.rootClass(person);
        GeneratedFieldModel created = person.addField(name, type, FieldCardinality.SINGLE);
        created.entityClassName(target);
        created.mapping().productionKind(kind);
        return created;
    }

    private static FieldSourcePanel editing(GeneratedFieldModel field) {
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(projectContaining(field, true));
        panel.edit(field);
        return panel;
    }

    private static GeneratedProjectModel projectContaining(
            GeneratedFieldModel field, boolean includeTargetClass) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel owner = new GeneratedClassModel("Owner");
        // The field has to be IN this project, or it has no declaring class here and
        // every rule that asks who owns it answers null.
        owner.fields().add(field);
        project.addClass(owner);
        project.rootClass(owner);
        if (includeTargetClass && !field.entityClassName().isBlank()
                && project.findClass(field.entityClassName()) == null) {
            project.addClass(new GeneratedClassModel(field.entityClassName()));
        }
        return project;
    }

    private static JComboBox<?> comboContaining(Container root, Object value) {
        ArrayDeque<Container> pending = new ArrayDeque<>();
        Set<Component> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            for (Component component : pending.removeFirst().getComponents()) {
                if (!seen.add(component)) continue;
                if (component instanceof JComboBox<?> combo) {
                    for (int i = 0; i < combo.getItemCount(); i++) {
                        if (value.equals(combo.getItemAt(i))) return combo;
                    }
                }
                if (component instanceof Container child) pending.addLast(child);
            }
        }
        throw new AssertionError("No combo contains " + value);
    }

    /** The labels a reader can actually see, in layout order. */
    private static List<String> visibleRowLabels(Container root) {
        List<String> labels = new ArrayList<>();
        collect(root, labels);
        return labels;
    }

    private static void collect(Container root, List<String> labels) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && component.isVisible()
                    && label.getText() != null && label.getText().endsWith(":")) {
                labels.add(label.getText());
            }
            if (component instanceof Container child
                    && !(component instanceof JComponent c && !c.isVisible())) {
                collect(child, labels);
            }
        }
    }
}
