package domain.transform.ui;

import domain.datasource.schema.FieldType;
import objectview.Viewable;
import objectview.render.GroupMembersView;
import objectview.view.SearchableView;
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

import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
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

        GroupMembersView groups = awaitGroup(panel[0], "Ennead");
        assertNotNull(groups, "the group class must render through the shared group tree");
        SwingUtilities.invokeAndWait(() ->
                groups.groups().getTree().setSelectionRows(new int[] {1, 2}));

        JLabel status = named(panel[0], "instance.scope.status", JLabel.class);
        assertNotNull(status);
        assertTrue(status.getText().contains("DeityGroup · 2 shown"), status.getText());
        assertEquals(2, groups.groups().selectedGroups().size());

        BoundedInstanceSelection selection = field(
                panel[0], "datasourceSelection", BoundedInstanceSelection.class);
        assertEquals(2, selection.size(),
                "the selected group nodes are immediately the datasource input");

        SearchableView memberCards = all(panel[0], SearchableView.class).stream()
                .filter(view -> view.cardList() != null
                        && view.cardList().getVirtualList().items().stream()
                                .anyMatch(value -> "Deity".equals(value.typeName())))
                .findFirst().orElseThrow();
        Viewable inspectedMember = memberCards.cardList()
                .getVirtualList().items().getFirst();
        SwingUtilities.invokeAndWait(() ->
                memberCards.renderContext().select(inspectedMember));
        assertEquals(2, selection.size(),
                "member-card inspection must not replace the selected group nodes");
        assertTrue(all(panel[0], JButton.class).stream()
                        .anyMatch(button -> "Fetch 2 selected articles"
                                .equals(button.getText())),
                "the datasource action must still read the two selected group nodes");
        JTree actions = named(panel[0], "workbench.action-result.tree", JTree.class);
        assertNotNull(actions);
        assertTrue(children(child(root(actions), "Wikipedia")).contains(
                        "Configured field evidence"),
                "datasources and their actions are visible without opening another window");
        assertTrue(named(panel[0], "domain.datasource.wikipedia", JButton.class) == null,
                "there is no second Wikipedia launcher beside the action tree");
        JSplitPane content = named(panel[0], "workbench.fields-and-content",
                JSplitPane.class);
        JSplitPane datasource = named(panel[0], "datasource.scope-actions-results",
                JSplitPane.class);
        assertEquals(320, content.getDividerLocation(),
                "DataSourceApp keeps the field chooser compact for the result pane");
        assertEquals(JSplitPane.VERTICAL_SPLIT, datasource.getOrientation(),
                "selected instances belong above the action tree and result");
        assertEquals(360, datasource.getDividerLocation(),
                "the upper instance scope starts at a useful inspection height");
        assertEquals(0.43, datasource.getResizeWeight(),
                "additional height remains shared between context and action result");
        assertNotNull(named((Container) datasource.getTopComponent(),
                "instance.scope.status", JLabel.class),
                "the selected instance scope is the top panel");
        assertNotNull(named((Container) datasource.getBottomComponent(),
                "workbench.action-result.tree", JTree.class),
                "the action tree and its right-hand detail occupy the lower panel");
    }

    @Test void selectedGroupShowsTheConfiguredEvidenceContainerWithoutInventingRecipes()
            throws Exception {
        TransformWorkbenchPanel[] panel = new TransformWorkbenchPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new TransformWorkbenchPanel(
                    fixture(), null, "Egyptian Gods", WorkbenchPurpose.DATASOURCE);
            JComboBox<?> classes = named(panel[0], "view.class", JComboBox.class);
            assertNotNull(classes);
            classes.setSelectedItem("DeityGroup");
        });

        GroupMembersView groups = awaitGroup(panel[0], "Ennead");
        assertNotNull(groups);
        JTree actions = named(panel[0], "workbench.action-result.tree", JTree.class);
        DefaultMutableTreeNode wikipedia = child(root(actions), "Wikipedia");
        DefaultMutableTreeNode configured = child(wikipedia,
                "Configured field evidence");
        SwingUtilities.invokeAndWait(() ->
                actions.setSelectionPath(new TreePath(configured.getPath())));
        assertEquals(0, configured.getChildCount(),
                "this in-memory domain carries no ModelBuilder evidence recipes; the UI "
                        + "must not invent a hidden default action");
        assertTrue(labels(panel[0]).stream().anyMatch(text ->
                        text.contains("No configured evidence recipes")),
                labels(panel[0]).toString());
    }

    /** Reported: staging persisted 42 AssessorOfMaat instances and their group links,
     * but the already-visible group panel kept rendering its pre-stage member list. */
    @Test void stagedMembersImmediatelyReplaceTheSelectedGroupsMemberPanel()
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

        GroupMembersView groups = awaitGroup(panel[0], "Ennead");
        assertNotNull(groups);
        objectview.group.ViewableGroup<?> ennead = groups.groups().getRootGroup()
                .getChildren().stream()
                .filter(group -> "Ennead".equals(group.getDisplayName()))
                .findFirst().orElseThrow();
        WikidataDynamicObject group = (WikidataDynamicObject) ennead.getKeyRef();
        WikidataDynamicObject added = domain.instances().stream()
                .filter(value -> "Q2".equals(value.getIdentifier()))
                .map(WikidataDynamicObject.class::cast).findFirst().orElseThrow();
        WikidataDynamicObject first = domain.instances().stream()
                .filter(value -> "Q1".equals(value.getIdentifier()))
                .map(WikidataDynamicObject.class::cast).findFirst().orElseThrow();

        SwingUtilities.invokeAndWait(() -> {
            group.put("members", List.of(first, added));
            panel[0].refreshAfterCuration();
        });

        assertTrue(all(panel[0], SearchableView.class).stream().anyMatch(view ->
                        view.cardList() != null
                                && view.cardList().getVirtualList().items().stream()
                                        .map(Viewable::getIdentifier)
                                        .toList().containsAll(List.of("Q1", "Q2"))),
                "the selected group's existing instance pane must show the staged members");
    }

    @Test void groupClassTreeSurvivesAnAbstractMemberBaseWithNoDirectInstances()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass().className("DeityGroupMember");
        GeneratedClassModel deity = new GeneratedClassModel("EgyptianDeity");
        deity.baseClassName("DeityGroupMember");
        model.addClass(deity);
        GeneratedClassModel groupClass = new GeneratedClassModel("DeityGroup");
        model.addClass(groupClass);
        groupClass.addField("members", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("DeityGroupMember");
        groupClass.groupSpec(new GroupClassSpec("members"));

        WikidataDynamicObject member = value("Q1", "EgyptianDeity", "Ra");
        WikidataDynamicObject group = value("Q2", "DeityGroup", "Ennead");
        group.put("members", List.of(member));
        List<Viewable> pool = List.of(group, member);
        ProductDomain domain = new ProductDomain(new ProductSchema(List.of(
                new ProductClass("DeityGroupMember", "DeityGroupMember", null,
                        true, List.of()),
                new ProductClass("EgyptianDeity", "EgyptianDeity",
                        "DeityGroupMember", true, List.of()),
                new ProductClass("DeityGroup", "DeityGroup", List.of(
                        new ProductField("members", "List<DeityGroupMember>", true,
                                true, "DeityGroupMember", false)))),
                // The abstract base deliberately is not a member class.
                List.of("EgyptianDeity", "DeityGroup")),
                pool, Viewable.class, null, java.util.Map.of(),
                ConfiguredGroups.rootBindings(model, pool));

        TransformWorkbenchPanel[] panel = new TransformWorkbenchPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new TransformWorkbenchPanel(
                    domain, null, "Egyptian Gods", WorkbenchPurpose.DATASOURCE);
            JComboBox<?> classes = named(panel[0], "view.class", JComboBox.class);
            assertNotNull(classes);
            classes.setSelectedItem("DeityGroup");
        });

        GroupMembersView groups = awaitGroup(panel[0], "Ennead");
        assertNotNull(groups);
        assertEquals(List.of("Ennead"), groups.groups().getRootGroup().getChildren()
                .stream().map(objectview.group.ViewableGroup::getDisplayName).toList(),
                "DeityGroup instances remain group nodes even when their member base "
                        + "has no direct instances");
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
        value.enwikiTitle(label);
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

    private static GroupMembersView awaitGroup(Container root, String child)
            throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            for (GroupMembersView view : all(root, GroupMembersView.class)) {
                if (view.groups().getRootGroup().getChildren().stream()
                        .anyMatch(group -> child.equals(group.getDisplayName()))) return view;
            }
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

    private static <T> List<T> all(Container root, Class<T> type) {
        java.util.ArrayList<T> result = new java.util.ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container nested) result.addAll(all(nested, type));
        }
        return result;
    }

    private static DefaultMutableTreeNode root(JTree tree) {
        return (DefaultMutableTreeNode) tree.getModel().getRoot();
    }

    private static DefaultMutableTreeNode child(
            DefaultMutableTreeNode parent, String label) {
        return java.util.stream.IntStream.range(0, parent.getChildCount())
                .mapToObj(index -> (DefaultMutableTreeNode) parent.getChildAt(index))
                .filter(node -> label.equals(node.toString())).findFirst().orElseThrow();
    }

    private static List<String> children(DefaultMutableTreeNode parent) {
        return java.util.stream.IntStream.range(0, parent.getChildCount())
                .mapToObj(parent::getChildAt).map(Object::toString).toList();
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<String> labels(Container root) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label) result.add(label.getText());
            if (child instanceof Container nested) result.addAll(labels(nested));
        }
        return result;
    }

    private static List<String> borderTitles(Container root) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JComponent component
                    && component.getBorder() instanceof
                            javax.swing.border.TitledBorder titled) {
                result.add(titled.getTitle());
            }
            if (child instanceof Container nested) result.addAll(borderTitles(nested));
        }
        return result;
    }
}
