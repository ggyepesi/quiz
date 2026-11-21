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

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A configured group class is selected as groups, not flattened cards. */
class BoundedInstanceSelectionGroupTest {

    @Test void groupSelectionShowsMembersBesideTheTreeAndSelectsTheGroupEntity()
            throws Exception {
        Fixture fixture = fixture();
        BoundedInstanceSelection[] selection = new BoundedInstanceSelection[1];

        SwingUtilities.invokeAndWait(() -> {
            selection[0] = new BoundedInstanceSelection(
                    new TransformController(fixture.domain(), null), new NoOpHost(),
                    20, 5, "groups", ignored -> true, "are configured groups");
            assertTrue(selection[0].populate("DeityGroup"));

            GroupMembersView available = find(
                    (Container) selection[0].tabs().getComponentAt(0),
                    GroupMembersView.class);
            assertNotNull(available, "the group class must use the shared group tree");
            assertNotNull(button(available, "Show group fields"));
            assertEquals(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION,
                    available.groups().getTree().getSelectionModel().getSelectionMode());
            available.groups().getTree().setSelectionRows(new int[] { 1, 2 });

            AbstractButton add = button(selection[0].tabs(), "Add selection");
            assertNotNull(add);
            assertTrue(add.isEnabled(), "selected group nodes are one batch action");
            add.doClick();

            assertEquals(2, selection[0].size());
            assertEquals(List.of(fixture.group(), fixture.otherGroup()),
                    selection[0].selected());
            GroupMembersView chosen = find(
                    (Container) selection[0].tabs().getComponentAt(1),
                    GroupMembersView.class);
            assertNotNull(chosen,
                    "the selected-groups tab keeps the same tree/member representation");
            chosen.groups().getTree().setSelectionRows(new int[] { 1, 2 });
            AbstractButton remove = button(selection[0].tabs(), "Remove selection");
            assertNotNull(remove);
            assertTrue(remove.isEnabled());
            remove.doClick();
            assertEquals(0, selection[0].size(),
                    "Remove selection acts on the selected group-node batch too");
        });
    }

    private static Fixture fixture() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel deityClass = model.rootClass();
        deityClass.className("Deity");
        GeneratedClassModel groupClass = new GeneratedClassModel("DeityGroup");
        model.addClass(groupClass);
        groupClass.addField("members", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Deity");
        groupClass.groupSpec(new GroupClassSpec("members"));

        WikidataDynamicObject deity = new WikidataDynamicObject("Q1", "A deity");
        deity.type("Deity");
        deity.directClasses(java.util.Set.of("Deity"));
        WikidataDynamicObject group = new WikidataDynamicObject("Q2", "Ennead");
        group.type("DeityGroup");
        group.directClasses(java.util.Set.of("DeityGroup"));
        group.put("members", List.of(deity));
        WikidataDynamicObject otherDeity = new WikidataDynamicObject("Q3", "Another deity");
        otherDeity.type("Deity");
        otherDeity.directClasses(java.util.Set.of("Deity"));
        WikidataDynamicObject otherGroup = new WikidataDynamicObject("Q4", "Ogdoad");
        otherGroup.type("DeityGroup");
        otherGroup.directClasses(java.util.Set.of("DeityGroup"));
        otherGroup.put("members", List.of(otherDeity));
        List<Viewable> pool = List.of(group, otherGroup, deity, otherDeity);

        List<objectview.viewconfig.DomainGroupRoot> roots =
                ConfiguredGroups.rootBindings(model, pool);
        ProductClass deityProduct = new ProductClass(
                "Deity", "Deity", List.of());
        ProductClass groupProduct = new ProductClass(
                "DeityGroup", "DeityGroup", List.of(new ProductField(
                        "members", "List<Deity>", true, true,
                        "Deity", false)));
        ProductDomain domain = new ProductDomain(
                new ProductSchema(List.of(deityProduct, groupProduct),
                        List.of("Deity", "DeityGroup")),
                pool, Viewable.class, null, java.util.Map.of(), roots);
        return new Fixture(domain, group, otherGroup);
    }

    private static AbstractButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                AbstractButton found = button(nested, text);
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

    private record Fixture(
            ProductDomain domain,
            WikidataDynamicObject group,
            WikidataDynamicObject otherGroup) { }

    private static final class NoOpHost implements BoundedInstanceSelection.Host {
        @Override public void selectionChanged(String reason) { }
        @Override public void updateActions() { }
    }
}
