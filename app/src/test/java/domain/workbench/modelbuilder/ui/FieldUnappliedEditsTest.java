package domain.workbench.modelbuilder.ui;

import domain.config.FieldCardinality;
import domain.config.FieldConfigurationProjection;
import domain.config.FieldProductionKind;
import domain.config.FieldRenderMode;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.GroupClassSpec;
import domain.compile.filter.WikidataValueFilterOperator;
import domain.datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import process.swing.Dialogs.UnappliedEdits;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The field editor compares the authored draft with the applied model even when the model
 * has errors. A production-site switch resolves the whole field draft and keeps the tree
 * selection when the switch is refused.
 */
class FieldUnappliedEditsTest {

    /** If Apply on unchanged controls changed anything, every field would ask. */
    @Test void everyShippedFieldOpensWithoutUnappliedEdits() throws Exception {
        List<String> dirty = new ArrayList<>();
        int[] fields = {0};
        try (var paths = Files.walk(Path.of("../data/wikidata"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".model.json")).sorted().toList()) {
                GeneratedProjectModel model = new GeneratedProjectModelStore().load(path.toFile());
                SwingUtilities.invokeAndWait(() -> {
                    FieldSourcePanel panel = new FieldSourcePanel();
                    panel.setProjectModel(model);
                    panel.showLocalFieldEditor();
                    for (GeneratedClassModel clazz : model.classes()) {
                        if (clazz.isImported()) continue;
                        for (GeneratedFieldModel field : clazz.fields()) {
                            panel.edit(field);
                            fields[0]++;
                            if (panel.hasUnappliedEdits()) {
                                dirty.add(path.getFileName() + " " + clazz.className() + "." + field.name());
                            }
                        }
                    }
                });
            }
        }
        assertTrue(fields[0] > 100, "the shipped models were read");
        assertEquals(List.of(), dirty);
    }

    @Test void anEditIsUnappliedUntilApplyAndSelectingNodesNeverMakesItDirty() throws Exception {
        var owned = owned();
        SwingUtilities.invokeAndWait(() -> {
            FieldSourcePanel panel = panel(owned);
            for (var node : panel.configurationTree().nodes()) {
                panel.configurationTree().select(node.id());
            }
            assertFalse(panel.hasUnappliedEdits(), "inspection is not an edit");

            required(panel).setSelected(true);
            assertTrue(panel.hasUnappliedEdits());
            assertFalse(owned.text.required(), "nothing is written before Apply");

            assertTrue(panel.applyUnappliedEdits());
            assertTrue(owned.text.required());
            assertFalse(panel.hasUnappliedEdits());

            property(panel).setText("P2561");
            assertTrue(panel.hasUnappliedEdits());
            property(panel).setText("P1559");
            assertFalse(panel.hasUnappliedEdits(), "editing a value and restoring it is clean");
        });
    }

    @Test void switchingProductionSiteResolvesTheWholeField() throws Exception {
        for (UnappliedEdits answer : UnappliedEdits.values()) {
            var owned = owned();
            List<String> asked = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                FieldSourcePanel panel = panel(owned);
                panel.unappliedDecision((construct, action) -> {
                    asked.add(action);
                    return answer;
                });
                // The shared definition and a source value: the field's one draft.
                cardinality(panel).setSelectedItem(FieldCardinality.COLLECTION);
                property(panel).setText("P2561");
                sites(panel).setSelectedIndex(1);

                assertEquals(List.of("switching to another production site"), asked, answer.name());
                switch (answer) {
                    case STAY -> {
                        assertEquals(0, sites(panel).getSelectedIndex(), "Stay keeps the site");
                        assertEquals(FieldCardinality.COLLECTION, cardinality(panel).getSelectedItem());
                        assertEquals("P2561", property(panel).getText(), "Stay keeps both edits");
                        assertEquals(FieldCardinality.SINGLE, owned.text.cardinality());
                        assertEquals("P1559", owned.text.mapping().propertyPid());
                    }
                    case DISCARD -> {
                        assertEquals(1, sites(panel).getSelectedIndex());
                        assertEquals(FieldCardinality.SINGLE, cardinality(panel).getSelectedItem(),
                                "a discarded edit does not stay on screen looking applied");
                        assertEquals(FieldCardinality.SINGLE, owned.text.cardinality());
                        assertEquals("P1559", owned.text.mapping().propertyPid());
                        assertFalse(panel.hasUnappliedEdits());
                    }
                    case APPLY -> {
                        assertEquals(1, sites(panel).getSelectedIndex());
                        assertEquals(FieldCardinality.COLLECTION, owned.text.cardinality(),
                                "Apply wrote the shared definition");
                        assertEquals("P2561", owned.text.mapping().propertyPid(),
                                "and the source value, before switching");
                        assertFalse(panel.hasUnappliedEdits());
                    }
                }
            });
        }
    }

    @Test void aModelErrorRefusesApplyAndDoesNotHideLaterFieldEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = projectWithRoot();
            var root = model.rootClass();
            var members = root.addField("members", FieldType.ENTITY, FieldCardinality.COLLECTION);
            members.entityClassName(root.className());
            members.mapping().propertyPid("P527");
            root.groupSpec(new GroupClassSpec("members"));
            var panel = panel(model, members);

            cardinality(panel).setSelectedItem(FieldCardinality.SINGLE);
            assertTrue(panel.hasUnappliedEdits());
            assertFalse(panel.applyUnappliedEdits());
            assertEquals(FieldCardinality.COLLECTION, members.cardinality());
            assertEquals(FieldCardinality.SINGLE, cardinality(panel).getSelectedItem());
            assertTrue(status(panel).contains("must be a list"), status(panel));

            // An existing model error must not make two different field values equal.
            members.cardinality(FieldCardinality.SINGLE);
            panel.edit(members);
            assertFalse(panel.hasUnappliedEdits());
            property(panel).setText("P361");
            assertTrue(panel.hasUnappliedEdits());
            assertEquals("P527", members.mapping().propertyPid());
        });
    }

    @Test void invalidNumericFilterRemainsAnUnappliedEditAndKeepsTheAppliedFilter()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = projectWithRoot();
            var size = model.rootClass().addField("size", FieldType.NUMBER, FieldCardinality.SINGLE);
            size.mapping().propertyPid("P1082");
            var panel = panel(model, size);
            combo(panel, "filterOpBox").setSelectedItem(">");
            var value = (JTextField) field(panel, "filterValueField");
            value.setText("ten");
            assertTrue(panel.hasUnappliedEdits());
            assertFalse(panel.applyUnappliedEdits());
            assertNull(size.filterOperator());
            assertEquals("ten", value.getText());
            assertTrue(status(panel).contains("Enter a number"), status(panel));

            value.setText("10");
            assertTrue(panel.applyUnappliedEdits());
            value.setText("ten");
            assertFalse(panel.applyUnappliedEdits());
            assertEquals(WikidataValueFilterOperator.GT, size.filterOperator());
            assertEquals(10.0, size.filterValue());
            assertEquals("ten", value.getText());
        });
    }

    @Test void stayingOnADirtyProductionSiteRestoresTheFieldTreeSelection() throws Exception {
        var owned = owned();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(owned);
            var tree = panel.configurationTree();
            assertTrue(tree.selectDetail(FieldConfigurationProjection.PRIMARY));
            var original = tree.selectedNode().id();
            var shared = tree.nodes().stream()
                    .filter(node -> node.sourceContext() != null && !node.id().equals(original))
                    .findFirst().orElseThrow();
            sites(panel).setSelectedIndex(1);
            property(panel).setText("P2561");
            List<String> asked = new ArrayList<>();
            panel.unappliedDecision((construct, action) -> {
                asked.add(action);
                return UnappliedEdits.STAY;
            });

            tree.select(shared.id());

            assertEquals(List.of("switching to another production site"), asked);
            assertEquals(original, tree.selectedNode().id());
            assertEquals(1, sites(panel).getSelectedIndex());
            assertEquals("P2561", property(panel).getText());
            assertTrue(panel.hasUnappliedEdits());
        });
    }

    @Test void applyingAnAutoWikidataImageKeepsTheInferredTypeAndRendering() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = projectWithRoot();
            var image = model.rootClass().addField("portrait", FieldType.AUTO, FieldCardinality.SINGLE);
            var panel = panel(model, image);
            property(panel).setText("P18");

            assertTrue(panel.applyUnappliedEdits());
            assertEquals(FieldType.IMAGE, image.type());
            assertEquals(FieldRenderMode.INLINE, image.renderMode());
            assertEquals(FieldProductionKind.INLINE_VALUE, image.mapping().productionKind());
            assertEquals(FieldType.IMAGE, combo(panel, "typeBox").getSelectedItem());
            assertFalse(panel.hasUnappliedEdits());
        });
    }

    private record Owned(GeneratedProjectModel project, GeneratedFieldModel text) { }

    private static GeneratedProjectModel projectWithRoot() {
        var model = new GeneratedProjectModel();
        var root = new GeneratedClassModel("Person");
        root.seedQids().add("Q1");
        model.rootClass(root);
        return model;
    }

    private static Owned owned() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.classKind(domain.config.ClassKind.OWNED);
        GeneratedFieldModel structuredName = person.addField(
                "structuredName", FieldType.ENTITY, FieldCardinality.SINGLE);
        structuredName.entityClassName("Name");
        structuredName.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedFieldModel text = name.addField("text", FieldType.TEXT, FieldCardinality.SINGLE);
        text.mapping().propertyPid("P1559");
        project.rootClass(person);
        project.addClass(name);
        project.ensureDeclarationIdentities();
        return new Owned(project, text);
    }

    private static FieldSourcePanel panel(Owned owned) {
        var panel = panel(owned.project(), owned.text());
        assertTrue(sites(panel).getItemCount() >= 2, "the owned field has a production site");
        return panel;
    }

    private static FieldSourcePanel panel(GeneratedProjectModel model, GeneratedFieldModel field) {
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(model);
        panel.showLocalFieldEditor();
        panel.edit(field);
        return panel;
    }

    private static String status(FieldSourcePanel panel) {
        return ((JLabel) field(panel, "applyStatusLabel")).getText();
    }

    private static JCheckBox required(FieldSourcePanel panel) {
        return (JCheckBox) field(panel, "requiredBox");
    }

    private static JComboBox<Object> cardinality(FieldSourcePanel panel) {
        return combo(panel, "shapeBox");
    }

    private static javax.swing.JTextField property(FieldSourcePanel panel) {
        return (javax.swing.JTextField) field(panel, "propertyPidField");
    }

    private static JComboBox<Object> sites(FieldSourcePanel panel) {
        return combo(panel, "sourceContextBox");
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<Object> combo(FieldSourcePanel panel, String name) {
        return (JComboBox<Object>) field(panel, name);
    }

    private static Object field(FieldSourcePanel panel, String name) {
        try {
            var declared = FieldSourcePanel.class.getDeclaredField(name);
            declared.setAccessible(true);
            return declared.get(panel);
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError(missing);
        }
    }
}
