package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test void aTypedEntityFieldWithAPropertyMayDeclareGraphExpansion() {
        GeneratedFieldModel spouse =
                field("spouse", FieldType.ENTITY, FieldProductionKind.AUTO, "Person");
        spouse.mapping().sourceType(FieldSourceType.SPARQL);
        spouse.mapping().propertyPid("P26");

        assertTrue(visibleRowLabels(editing(spouse)).contains("Graph expansion:"),
                "the row appears exactly where the traversal rule says it could apply");
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
        panel.edit(field);
        return panel;
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
