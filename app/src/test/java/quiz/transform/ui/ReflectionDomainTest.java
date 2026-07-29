package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import objectview.annotations.Reference;
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
}
