package quiz.transform.ui;

import datasource.schema.FieldType;
import objectview.Viewable;
import objectview.render.GroupMembersView;
import org.junit.jupiter.api.Test;
import quiz.transform.app.ConfiguredGroups;
import quiz.transform.app.ProductClass;
import quiz.transform.app.ProductDomain;
import quiz.transform.app.ProductField;
import quiz.transform.app.ProductSchema;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GroupClassSpec;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DataSourceApp scopes a modeled group class from its tree, not duplicate group cards. */
class TransformWorkbenchGroupClassTest {

    @Test void selectedGroupNodesAreTheDatasourceScopeWhileMembersStayBesideTheTree()
            throws Exception {
        ProductDomain domain = fixture();
        TransformWorkbenchPanel[] panel = new TransformWorkbenchPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new TransformWorkbenchPanel(
                    domain, null, "Egyptian Gods", WorkbenchPurpose.DATASOURCE);
            JComboBox<?> classes = named(panel[0], "view.class", JComboBox.class);
            assertNotNull(classes);
            classes.setSelectedItem("DeityGroup");
        });

        GroupMembersView groups = await(panel[0], GroupMembersView.class);
        assertNotNull(groups, "the group class must render through the shared group tree");
        SwingUtilities.invokeAndWait(() ->
                groups.groups().getTree().setSelectionRows(new int[] {1, 2}));

        JLabel status = named(panel[0], "instance.scope.status", JLabel.class);
        assertNotNull(status);
        assertTrue(status.getText().contains("DeityGroup · 2 shown"), status.getText());
        assertEquals(2, groups.groups().selectedGroups().size());
    }

    private static ProductDomain fixture() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("Deity");
        GeneratedClassModel groupClass = new GeneratedClassModel("DeityGroup");
        model.addClass(groupClass);
        groupClass.addField("members", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Deity");
        groupClass.groupSpec(new GroupClassSpec("members"));

        WikidataDynamicObject firstMember = value("Q1", "Deity", "First deity");
        WikidataDynamicObject secondMember = value("Q2", "Deity", "Second deity");
        WikidataDynamicObject firstGroup = value("Q3", "DeityGroup", "Ennead");
        firstGroup.put("members", List.of(firstMember));
        WikidataDynamicObject secondGroup = value("Q4", "DeityGroup", "Ogdoad");
        secondGroup.put("members", List.of(secondMember));
        List<Viewable> pool = List.of(firstGroup, secondGroup, firstMember, secondMember);
        List<objectview.viewconfig.DomainGroupRoot> roots =
                ConfiguredGroups.rootBindings(model, pool);
        return new ProductDomain(new ProductSchema(List.of(
                new ProductClass("DeityGroup", "DeityGroup", List.of(new ProductField(
                        "members", "List<Deity>", true, true, "Deity", false))),
                new ProductClass("Deity", "Deity", List.of())),
                List.of("DeityGroup", "Deity")), pool, Viewable.class,
                null, java.util.Map.of(), roots);
    }

    private static WikidataDynamicObject value(String id, String type, String label) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, label);
        value.type(type);
        value.directClasses(java.util.Set.of(type));
        return value;
    }

    private static <T> T await(Container root, Class<T> type) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            T found = find(root, type);
            if (found != null) return found;
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> { });
        }
        return null;
    }

    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) {
                return type.cast(child);
            }
            if (child instanceof Container nested) {
                T found = named(nested, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T find(Container root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
