package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import objectview.annotations.Reference;
import objectview.field.FieldKind;
import objectview.media.ImagePane;
import quiz.ViewableGroup;
import objectview.ViewableAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test void doesNotPromoteViewGroupsToDomainClasses() {
        Country c = new Country("Wonderland");
        c.group = new ViewableGroup("All").getOrCreateChild("Countries");

        ReflectionDomain domain = new ReflectionDomain(List.of(c));

        assertEquals(List.of("Country"), domain.types());
        assertEquals(List.of(c), domain.instances());
        assertFalse(domain.types().contains("ViewableGroup"));
        assertEquals(java.util.Set.of("group"),
                domain.structuralFields("Country"));
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
