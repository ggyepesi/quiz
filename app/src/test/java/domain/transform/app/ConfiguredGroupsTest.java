package domain.transform.app;

import domain.datasource.schema.FieldType;
import objectview.field.FieldSet;
import objectview.render.Card;
import objectview.render.LinkRow;
import objectview.render.ReferenceRow;
import objectview.render.RenderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataDynamicObjectJsonStore;
import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.GroupClassSpec;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A configured group keeps ordinary entity rendering; it is not a second card type. */
class ConfiguredGroupsTest {

    @Test void aConfiguredMemberFieldProducesAGroupWithThoseInstances() {
        Fixture fixture = fixture();

        ConfiguredGroups.Binding binding = ConfiguredGroups.materialize(
                fixture.model(), List.of(fixture.group(), fixture.member())).getFirst();

        assertEquals("DeityGroup", binding.groupClass());
        assertEquals("Deity", binding.memberClass());
        assertEquals(List.of(fixture.member()), List.copyOf(binding.root().getMembers()));
    }

    @Test void aCategoryPopulationNamesTheOneRootGroup() {
        Fixture fixture = fixture();
        fixture.model().findClass("DeityGroup").populationSource(
                new domain.datasource.api.SourceRecipe("wikipedia", "category-members",
                        java.util.Map.of("category",
                                "Category:Groups of Egyptian deities", "limit", "100")));

        ConfiguredGroups.Binding binding = ConfiguredGroups.materialize(
                fixture.model(), List.of(fixture.group(), fixture.member())).getFirst();

        assertEquals("Groups of Egyptian deities", binding.root().getDisplayName());
        assertEquals(1, binding.root().getChildren().size());
    }

    @Test void rootBindingsAssociateConfiguredGroupsWithTheirMemberClass() {
        Fixture fixture = fixture();

        objectview.viewconfig.DomainGroupRoot binding = ConfiguredGroups.rootBindings(
                fixture.model(), List.of(fixture.group(), fixture.member())).getFirst();

        assertEquals("Deity", binding.memberType());
        assertEquals(List.of(fixture.member()), List.copyOf(binding.root().getMembers()));
    }

    @Test void aLoadedDomainCanFindTheExistingGroupClassAdapter() {
        Fixture fixture = fixture();
        List<objectview.viewconfig.DomainGroupRoot> roots = ConfiguredGroups.rootBindings(
                fixture.model(), List.of(fixture.group(), fixture.member()));

        ConfiguredGroups.Binding binding = ConfiguredGroups.bindingForGroupClass(
                roots, "DeityGroup");

        assertNotNull(binding);
        assertEquals("Deity", binding.memberClass());
        objectview.group.ViewableGroup<?> ennead =
                binding.root().getChildren().iterator().next();
        assertEquals(fixture.group(), ennead.getKeyRef(),
                "selecting a group node selects its configured group entity");
        assertEquals(List.of(fixture.member()), List.copyOf(ennead.getMembers()));
    }

    @Test void equallyNamedConfiguredClassNodesKeepTheirModelIdentity() {
        Fixture fixture = fixture();
        GeneratedClassModel second = new GeneratedClassModel("OtherDeityGroup");
        second.alias("DeityGroup");
        fixture.model().addClass(second);
        second.addField("members", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("Deity");
        second.groupSpec(new GroupClassSpec("members"));
        WikidataDynamicObject other = new WikidataDynamicObject("Q3", "Other group");
        other.type("OtherDeityGroup");
        other.directClasses(java.util.Set.of("OtherDeityGroup"));
        other.put("members", List.of(fixture.member()));

        objectview.group.ViewableGroup<?> root = ConfiguredGroups.rootBindings(
                fixture.model(), List.of(
                        fixture.group(), other, fixture.member())).getFirst().root();
        List<? extends objectview.group.ViewableGroup<?>> classes =
                List.copyOf(root.getChildren());

        assertEquals(List.of("DeityGroup groups", "DeityGroup groups"),
                classes.stream().map(objectview.Viewable::getDisplayName).toList());
        assertNotEquals(classes.get(0).getIdentifier(), classes.get(1).getIdentifier());
        assertEquals(2, root.getChildrenMap().size(),
                "same display labels must not collapse two configured group classes");
    }

    /**
     * The reported shape: TransformApp copied the configured groups into its editable
     * tree as manual groups, Save wrote them, and the saved copy then replaced the
     * configured root on every load, so a regenerated Ennead never reached TransformApp,
     * the quiz or the web. Configured groups now stay live in the working tree, Save
     * skips them, and each load adds the rebuilt ones beside the authored groups.
     */
    @Test void aSavedTreeKeepsItsAuthoredGroupsWhileConfiguredGroupsAreRebuiltNotSaved(
            @TempDir Path dir) throws Exception {
        Fixture fixture = fixture();
        List<objectview.viewconfig.DomainGroupRoot> configured =
                ConfiguredGroups.rootBindings(
                        fixture.model(), List.of(fixture.group(), fixture.member()));
        String classGroup = ConfiguredGroups.materialize(fixture.model(),
                List.of(fixture.group(), fixture.member())).getFirst().root().getDisplayName();

        domain.transform.EditableGroup working =
                domain.transform.EditableGroup.copyOf(configured.getFirst().root());
        working.addGroup(new domain.transform.EditableGroup("Favourites"));
        assertEquals(List.of(classGroup, "Favourites"), names(working));
        assertTrue(working.getChildren().stream()
                        .anyMatch(domain.transform.ConfiguredGroup.class::isInstance),
                "the working copy keeps the configured group itself, not a manual copy");

        objectview.group.ViewableGroup<?> saved = saveAndLoad(
                working, fixture, List.of(fixture.group(), fixture.member()),
                dir.resolve("first.snapshot.json"));
        assertEquals(List.of("Favourites"), names(saved), "Save writes only authored groups");

        // Regenerated: the Ennead gained a member.
        WikidataDynamicObject added = new WikidataDynamicObject("Q2", "Another deity");
        added.type("Deity");
        added.directClasses(java.util.Set.of("Deity"));
        fixture.group().put("members", List.of(fixture.member(), added));
        List<objectview.viewconfig.DomainGroupRoot> reloaded = ConfiguredGroups.combine(
                ConfiguredGroups.rootBindings(fixture.model(),
                        List.of(fixture.group(), fixture.member(), added)),
                List.of(new objectview.viewconfig.DomainGroupRoot("Deity", saved)));

        assertEquals(1, reloaded.size(), "one root per member type");
        objectview.group.ViewableGroup<?> root = reloaded.getFirst().root();
        assertEquals(List.of("Favourites", classGroup), names(root));
        assertEquals(List.of("Q1", "Q2"), root.getMembers().stream()
                        .map(objectview.Viewable::getIdentifier).toList(),
                "the All root follows the current regenerated class population");
        objectview.group.ViewableGroup<?> ennead = root.getChildren().stream()
                .filter(child -> classGroup.equals(child.getDisplayName()))
                .findFirst().orElseThrow().getChildren().iterator().next();
        assertEquals(List.of("Q1", "Q2"), ennead.getMembers().stream()
                .map(value -> ((objectview.Viewable) value).getIdentifier()).toList());

        assertEquals(List.of("Favourites"), names(saveAndLoad(
                        domain.transform.EditableGroup.copyOf(root), fixture,
                        List.of(fixture.group(), fixture.member(), added),
                        dir.resolve("second.snapshot.json"))),
                "saving again still writes no configured group, so nothing duplicates");
    }

    @Test void aMemberTypeWithoutASavedTreeGetsAnAllRootOverItsInstances() {
        Fixture fixture = fixture();

        objectview.viewconfig.DomainGroupRoot binding = ConfiguredGroups.combine(
                ConfiguredGroups.rootBindings(
                        fixture.model(), List.of(fixture.group(), fixture.member())),
                List.of()).getFirst();

        assertEquals("All Deity", binding.root().getDisplayName());
        assertEquals(List.of(fixture.member()), List.copyOf(binding.root().getMembers()));
    }

    private static objectview.group.ViewableGroup<?> saveAndLoad(
            domain.transform.EditableGroup working, Fixture fixture,
            List<WikidataDynamicObject> memberRoots, Path file) throws Exception {
        ViewableToWdo.ConvertedDomain converted = ViewableToWdo.convertDomain(
                memberRoots,
                List.of(new objectview.viewconfig.DomainGroupRoot("Deity", working)), null);
        List<WikidataDynamicObjectJsonStore.GroupRootBinding> bindings =
                converted.groupRootBindings().stream()
                        .map(binding -> new WikidataDynamicObjectJsonStore.GroupRootBinding(
                                binding.memberType(), binding.root())).toList();
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.saveWithGroupRootBindings(
                converted.memberRoots(), bindings, file.toFile(), null);
        WikidataDynamicObjectJsonStore.LoadedSnapshot loaded =
                store.loadAllWithFieldGraph(file.toFile());
        return DynamicViewableGroup.adapt(
                loaded.groupRootBindings().getFirst().root());
    }

    private static List<String> names(objectview.group.ViewableGroup<?> group) {
        return group.getChildren().stream()
                .map(objectview.group.ViewableGroup::getDisplayName).toList();
    }

    @Test void aLinkedDisplayCaptionHasNoEmptyDisclosure() throws Exception {
        Fixture fixture = fixture();
        objectview.group.ViewableGroup<?> group = ConfiguredGroups.materialize(
                fixture.model(), List.of(fixture.group(), fixture.member()))
                .getFirst().root().getChildren().iterator().next();
        Object sources = FieldSet.of(group).read("wikidataSource");
        RenderContext context = new RenderContext();
        context.setCollectionExpanded(sources, true);
        Card[] rendered = new Card[1];

        SwingUtilities.invokeAndWait(() -> rendered[0] =
                new Card(group, null, context, false, true));

        LinkRow link = find(rendered[0], LinkRow.class);
        assertNotNull(link);
        assertEquals("https://www.wikidata.org/wiki/Q233458", link.url());
        assertNull(find(rendered[0], ReferenceRow.class),
                "a display-only linked object is a link, not an empty expander");
    }

    private static Fixture fixture() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Groups");
        GeneratedClassModel deity = model.rootClass();
        deity.className("Deity");
        GeneratedClassModel groups = new GeneratedClassModel("DeityGroup");
        model.addClass(groups);
        GeneratedFieldModel members = groups.addField(
                "members", FieldType.ENTITY, FieldCardinality.COLLECTION);
        members.entityClassName("Deity");
        groups.groupSpec(new GroupClassSpec("members"));

        WikidataDynamicObject member = new WikidataDynamicObject("Q1", "A deity");
        member.type("Deity");
        member.directClasses(java.util.Set.of("Deity"));
        WikidataDynamicObject group = new WikidataDynamicObject("Q233458", "Ennead");
        group.type("DeityGroup");
        group.directClasses(java.util.Set.of("DeityGroup"));
        group.put("members", List.of(member));
        return new Fixture(model, group, member);
    }

    private static <T> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private record Fixture(
            GeneratedProjectModel model,
            WikidataDynamicObject group,
            WikidataDynamicObject member) { }
}
