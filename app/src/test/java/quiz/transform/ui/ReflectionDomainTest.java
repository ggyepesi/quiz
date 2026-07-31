package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import objectview.annotations.Reference;
import objectview.field.FieldKind;
import objectview.media.ImagePane;
import objectview.viewconfig.ConfigFieldRowSource;
import objectview.viewconfig.FieldRowContext;
import objectview.viewconfig.ViewConfig;
import objectview.viewconfig.DomainViews;
import quiz.ViewableGroup;
import objectview.ViewableAdapter;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReflectionDomain exposes EVERY class reachable from the roots — the main class
 * and its referenced ones (like Person/Terms) — each as a selectable type with its
 * own instances, not just the top-level roots.
 */
class ReflectionDomainTest {

    static class Lang extends ViewableAdapter {
        private final String langName;
        Lang(String n) { this.langName = n; }
        @Override public String getIdentifier() { return langName; }
        @Override public String getDisplayName() { return langName; }
    }

    static class Country extends ViewableAdapter {
        private final String countryName;
        private Lang language;
        @Reference private ViewableGroup group;
        Country(String n) { this.countryName = n; }
        @Override public String getIdentifier() { return countryName; }
        @Override public String getDisplayName() { return countryName; }
    }

    static class EmptyCountry extends ViewableAdapter {
        private final List<Lang> languages = new java.util.ArrayList<>();
        private final List<ImagePane> flags = new java.util.ArrayList<>();
        @Override public String getIdentifier() { return "empty"; }
        @Override public String getDisplayName() { return "Empty"; }
    }

    @Test void exposesReferencedClassesAndTheirInstances() {
        Country c = new Country("Wonderland");
        c.language = new Lang("English");

        ReflectionDomain domain = new ReflectionDomain(List.of(c));

        assertTrue(domain.types().contains("Country"), domain.types().toString());
        assertTrue(domain.types().contains("Lang"), domain.types().toString());

        // The reachable Lang is a first-class instance (selectable as a member).
        assertEquals(2, domain.instances().size());
        assertTrue(domain.instances().stream().anyMatch(q -> "English".equals(q.getDisplayName())));
    }

    @Test void treatsViewGroupsAsOrdinaryReachableViewables() {
        Country c = new Country("Wonderland");
        c.group = new ViewableGroup("All").getOrCreateChild("Countries");

        ReflectionDomain domain = new ReflectionDomain(List.of(c));

        assertEquals(List.of("Country", "ViewableGroup"), domain.types());
        assertTrue(domain.instances().size() >= 2);
        assertTrue(domain.instances().contains(c.group));
        assertTrue(domain.instances().stream()
                .anyMatch(value -> "All".equals(value.getIdentifier())));
        assertEquals(java.util.Set.of(), domain.structuralFields("Country"));
        assertTrue(domain.fields("Country").stream()
                .anyMatch(field -> "group".equals(field.field())));
        assertTrue(ConfigFieldRowSource.INSTANCE.rows(new FieldRowContext(
                        ViewConfig.all(Country.class), c, false, false, Set.of(),
                        domain.fieldTypes("Country"))).stream()
                .anyMatch(row -> "group".equals(row.path())),
                "group references must appear in field configuration");
    }

    @Test void domainViewsDeclareTheGroupRootAndReachChildrenThroughReferences()
            throws Exception {
        Country c = new Country("Wonderland");
        ViewableGroup root = new ViewableGroup("All");
        ViewableGroup child = root.getOrCreateChild("Countries");
        child.addMember(c);
        DomainViews views = new DomainViews() {
            @Override public void buildViews() {}
            @Override public objectview.render.GroupView getGroupView() { return null; }
            @Override public List<? extends objectview.group.ViewableGroup<?>>
                    getRootGroups() {
                return List.of(root);
            }
            @Override public Map<String, ? extends objectview.Viewable> getViewables() {
                return Map.of(c.getIdentifier(), c);
            }
        };

        ReflectionDomain domain = ReflectionDomain.of(views);

        assertEquals(List.of(root), domain.groupRoots());
        assertSame(root, domain.groupRoot("Country"));
        assertEquals("Country", domain.groupRootBindings().get(0).memberType());
        assertTrue(domain.instances().contains(root));
        assertTrue(domain.instances().contains(child));
    }

    @Test void emptyCollectionsKeepTheirDeclaredElementSchema() {
        ReflectionDomain domain =
                new ReflectionDomain(List.of(new EmptyCountry()));

        var languages = domain.fieldSchema("EmptyCountry").field("languages");
        assertTrue(languages.collection());
        assertTrue(languages.reference());
        assertEquals("Lang", languages.targetType());
        assertEquals(FieldKind.REFERENCE, languages.valueKind());
        assertTrue(domain.fields("EmptyCountry").stream()
                .anyMatch(field -> "languages.langName".equals(field.field())));

        var flags = domain.fieldSchema("EmptyCountry").field("flags");
        assertTrue(flags.collection());
        assertEquals(FieldKind.MEDIA, flags.valueKind());
        assertEquals("Collection<ImagePane>", flags.typeLabel());
    }
}
