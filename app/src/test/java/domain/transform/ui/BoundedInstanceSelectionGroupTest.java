package domain.transform.ui;

import domain.datasource.schema.FieldType;
import objectview.Viewable;
import objectview.render.GroupMembersView;
import org.junit.jupiter.api.Test;
import domain.transform.app.ConfiguredGroups;
import domain.transform.app.ProductClass;
import domain.transform.app.ProductDomain;
import domain.transform.app.ProductField;
import domain.transform.app.ProductSchema;
import domain.runtime.WikidataDynamicObject;
import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.GroupClassSpec;

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

    /** Egyptian gods types DeityGroup.members by a base with no fields of its own. The
     *  member cards then showed only what that empty base declares, because this view
     *  built no per-subclass configuration; the deities' own fields must reach it. */
    @Test void membersTypedByAFieldlessBaseKeepTheirSubclassFields() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("DeityGroupMember");
        GeneratedClassModel deityClass = new GeneratedClassModel("Deity");
        deityClass.baseClassName("DeityGroupMember");
        model.addClass(deityClass);
        GeneratedClassModel groupClass = new GeneratedClassModel("DeityGroup");
        model.addClass(groupClass);
        groupClass.addField("members", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("DeityGroupMember");
        groupClass.groupSpec(new GroupClassSpec("members"));

        WikidataDynamicObject deity = new WikidataDynamicObject("Q1", "Ra");
        deity.type("Deity");
        deity.directClasses(java.util.Set.of("Deity"));
        deity.put("epithet", "the sun");
        WikidataDynamicObject group = new WikidataDynamicObject("Q2", "Ennead");
        group.type("DeityGroup");
        group.directClasses(java.util.Set.of("DeityGroup"));
        group.put("members", List.of(deity));
        List<Viewable> pool = List.of(group, deity);
        ProductDomain domain = new ProductDomain(
                new ProductSchema(List.of(
                        new ProductClass("DeityGroupMember", "DeityGroupMember",
                                null, true, List.of()),
                        new ProductClass("Deity", "Deity", "DeityGroupMember", true,
                                List.of(new ProductField("epithet", "String",
                                        false, false, "", false))),
                        new ProductClass("DeityGroup", "DeityGroup", List.of(
                                new ProductField("members", "List<DeityGroupMember>",
                                        true, true, "DeityGroupMember", false)))),
                        List.of("DeityGroupMember", "Deity", "DeityGroup")),
                pool, Viewable.class, null, java.util.Map.of(),
                ConfiguredGroups.rootBindings(model, pool));

        SwingUtilities.invokeAndWait(() -> {
            BoundedInstanceSelection selection = new BoundedInstanceSelection(
                    new TransformController(domain, null), new NoOpHost(),
                    20, 5, "groups", ignored -> true, "are configured groups");
            assertTrue(selection.populate("DeityGroup"));
            GroupMembersView available = find(
                    (Container) selection.tabs().getComponentAt(0), GroupMembersView.class);
            assertNotNull(available);
            objectview.view.SearchableView members = find(
                    (Container) available.getComponent(0),
                    objectview.view.SearchableView.class);
            assertNotNull(members, "the lead group's members are shown beside the tree");
            assertTrue(members.configState().subtypeView().containsKey("Deity"),
                    "a Deity member is configured with its own fields, not only the base's");
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
