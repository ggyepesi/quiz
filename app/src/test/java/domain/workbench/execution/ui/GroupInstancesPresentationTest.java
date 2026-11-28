package domain.workbench.execution.ui;

import dataset.DomainStorage;
import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.config.GroupClassSpec;
import domain.datasource.schema.FieldType;
import domain.runtime.SnapshotDomain;
import domain.runtime.WikidataDynamicObject;
import domain.transform.app.DomainCatalog;
import objectview.demo.MultiView;
import objectview.render.GroupMembersView;
import objectview.render.GroupView;
import objectview.virtual.VirtualizedCardList;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GroupInstancesPresentationTest {
    @Test void groupsUseTwoSlotsAndTheirMembersAndDividerArrowsWork() throws Exception {
        var model = new GeneratedProjectModel();
        model.rootClass().className("Character");
        var groupClass = new GeneratedClassModel("CharacterGroup");
        groupClass.addField("members", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Character");
        groupClass.groupSpec(new GroupClassSpec("members"));
        model.addClass(groupClass);
        for (String type : List.of("Relic", "Event", "Location")) {
            model.addClass(new GeneratedClassModel(type));
        }
        var member = instance("Q1", "Character", "A character");
        var group = instance("Q2", "CharacterGroup", "A group");
        group.put("members", List.of(member));
        var panel = new QueryObjectResultPanel();
        var domain = new SnapshotDomain(List.of(instance("Q3", "Relic", "A relic"),
                group, instance("Q4", "Event", "An event"), member,
                instance("Q5", "Location", "A location")));
        SwingUtilities.invokeAndWait(() ->
                ProjectInstancesPresentation.showSaved(panel, model, domain, List.of()));

        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(1100, 760);
            layout(panel);
            var tabs = find(panel, JTabbedPane.class, value -> "objectview.sections.tabs".equals(value.getName()));
            assertNotNull(tabs);
            assertEquals("Relic · CharacterGroup · Event", tabs.getTitleAt(0));
            assertEquals("Character · Location", tabs.getTitleAt(1));
            var groupView = find(panel, GroupView.class,
                    g -> g.getRootGroup().getDisplayName().contains("CharacterGroup"));
            var relic = find((Container) tabs.getComponentAt(0), javax.swing.JPanel.class,
                    p -> p.getBorder() instanceof javax.swing.border.TitledBorder b
                            && b.getTitle().startsWith("Relic"));
            assertNotNull(groupView);
            assertNotNull(relic);
            var initialCards = find(groupView, VirtualizedCardList.class, value -> true);
            assertNotNull(initialCards, "The initial group's members are shown without a click.");
            assertEquals(List.of(member), initialCards.items());
            assertTrue(Math.abs(groupView.getWidth() - 2 * relic.getWidth()) < 30,
                    "The group section should be about twice the width of an ordinary class.");

            var multi = find(panel, MultiView.class, value -> true);
            multi.visibleSections(Set.of("Relic", "CharacterGroup", "Event", "Location"));
            layout(panel);
            groupView.getTree().setSelectionRow(1);
            var cards = showMembers(panel, groupView);
            assertEquals(List.of(member), cards.items());
            assertTrue(!panel.displayedObjectsByType().containsKey("Character"),
                    "the group's own members can be inspected without reopening the member class");

            var host = find(groupView, GroupMembersView.class, value -> true);
            var split = (JSplitPane) host.getComponent(0);
            assertSame(groupView.getMainPanel(), split.getLeftComponent());
            assertTrue(split.isOneTouchExpandable());
            var divider = ((BasicSplitPaneUI) split.getUI()).getDivider();
            var arrows = Arrays.stream(divider.getComponents())
                    .filter(JButton.class::isInstance).map(JButton.class::cast).toList();
            assertEquals(2, arrows.size());
            arrows.get(0).doClick();
            layout(panel);
            assertTrue(split.getLeftComponent().getWidth() <= 2, "collapse the outline");
            arrows.get(1).doClick();
            layout(panel);
            assertVisibleSplit(split);
            arrows.get(1).doClick();
            layout(panel);
            assertTrue(split.getRightComponent().getWidth() <= 2, "collapse the member pane");
            arrows.get(0).doClick();
            layout(panel);
            assertVisibleSplit(split);
            multi.visibleSections(Set.of("Relic", "Event", "Character", "Location"));
            assertTrue(panel.activeRenderContext().focusTopLevel(group),
                    "a configured group instance has its ordinary details card in the outline");
            assertTrue(panel.displayedObjectsByType().containsKey("CharacterGroup"));
            assertTrue(host.groups().isGroupDetailsExpanded(groupView.selectedGroup()));
            assertEquals("A group", groupView.selectedGroup().getDisplayName());
        });
    }

    @Test void savedNorseGroupsDisplayMembersWhenTheLocalSnapshotIsAvailable() throws Exception {
        var folder = new File("../data/wikidata/norsemythology");
        var snapshot = new File(folder, "norsemythology.snapshot.json");
        assumeTrue(snapshot.isFile(), "The local Norse snapshot is not checked in");
        var modelFile = new File(folder, "norsemythology.model.json");
        var domain = DomainCatalog.open(snapshot, modelFile, DomainStorage.in(folder));
        var model = new GeneratedProjectModelStore().load(modelFile);
        var panel = new QueryObjectResultPanel();
        SwingUtilities.invokeAndWait(() ->
                ProjectInstancesPresentation.showSaved(panel, model, domain, List.of()));

        SwingUtilities.invokeAndWait(() -> {
            panel.setSize(1100, 760);
            var tabs = find(panel, JTabbedPane.class, value -> "objectview.sections.tabs".equals(value.getName()));
            assertNotNull(tabs);
            var group = find(panel, GroupView.class,
                    g -> g.getRootGroup().getDisplayName().contains("NorseGroup"));
            assertNotNull(group);
            Component page = group;
            while (page.getParent() != tabs) page = page.getParent();
            tabs.setSelectedComponent(page);
            layout(panel);
            var cards = find(group, VirtualizedCardList.class, value -> true);
            assertNotNull(cards, "The NorseGroup tab starts with its root members visible.");
            assertEquals(group.getRootGroup().getMembers().size(), cards.items().size());
            assertTrue(!cards.items().isEmpty(), "Saved Norse groups contain members.");
        });
    }

    private static WikidataDynamicObject instance(String id, String type, String name) {
        var instance = new WikidataDynamicObject(id, name);
        instance.type(type);
        instance.directClasses(Set.of(type));
        return instance;
    }

    private static VirtualizedCardList showMembers(QueryObjectResultPanel panel, GroupView group) {
        var show = find(group, JButton.class, b -> "Show instances".equals(b.getText()));
        assertNotNull(show);
        show.doClick();
        layout(panel);
        var cards = find(group, VirtualizedCardList.class, value -> true);
        assertNotNull(cards, "Show instances must mount the shared card list.");
        var split = (JSplitPane) find(group, GroupMembersView.class, value -> true).getComponent(0);
        assertVisibleSplit(split);
        return cards;
    }

    private static void assertVisibleSplit(JSplitPane split) {
        assertTrue(split.getLeftComponent().getWidth() > 80, "The group outline needs room.");
        assertTrue(split.getRightComponent().getWidth() > 80, "The member pane needs room.");
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container c) layout(c);
    }

    private static <T> T find(Container root, Class<T> type, Predicate<T> filter) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && filter.test(type.cast(child))) return type.cast(child);
            if (child instanceof Container c) {
                T found = find(c, type, filter);
                if (found != null) return found;
            }
        }
        return null;
    }
}
