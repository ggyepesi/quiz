package quiz.curation;

import objectview.annotations.Reference;
import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergesTest {

    /** A State-shaped fixture: identity name + two image lists, one populated on each
     *  of the primary/duplicate so the merge must union them (the Tanzania case). */
    @SuppressWarnings("unused")
    static class Country extends ViewableAdapter {
        private final String name;
        @Reference private final List<String> flags = new ArrayList<>();
        @Reference private final List<String> shapes = new ArrayList<>();

        Country(String name) { this.name = name; }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    static class Holder extends ViewableAdapter {
        private final String name;
        private Country country;

        Holder(String name, Country country) {
            this.name = name;
            this.country = country;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    static class Tagged extends ViewableAdapter {
        private final String name;
        private final Set<String> tags = new TreeSet<>();

        Tagged(String name) { this.name = name; }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @Test
    void foldsDuplicateIntoPrimaryAndRemovesIt() {
        Country primary = new Country("Tanzania");
        primary.flags.add("flag.svg");                      // has a flag, no shape

        Country duplicate = new Country("Tanzania, United Republic of");
        duplicate.shapes.add("shape.svg");                  // has a shape, no flag

        List<Country> pool = new ArrayList<>(List.of(primary, duplicate));

        int merged = Merges.apply(pool,
                List.of(new Merge("Tanzania", "Tanzania, United Republic of", Merge.MANUAL)));

        assertEquals(1, merged);
        assertEquals(1, pool.size());                       // duplicate gone
        assertTrue(pool.contains(primary));
        assertFalse(pool.contains(duplicate));
        assertEquals(List.of("flag.svg"), primary.flags);   // kept its own flag
        assertEquals(List.of("shape.svg"), primary.shapes); // gained the duplicate's shape
    }

    @Test
    void perFieldSourceOverridesTheDefaultUnion() {
        Country primary = new Country("A");
        primary.flags.add("A-flag");
        Country duplicate = new Country("B");
        duplicate.flags.add("B-flag");                      // both have a flag → conflict

        List<Country> pool = new ArrayList<>(List.of(primary, duplicate));

        // Default for two collections is BOTH (union); ask for the DUPLICATE's instead.
        Merges.apply(pool, List.of(new Merge("A", "B",
                Map.of("flags", Merge.DUPLICATE), Merge.MANUAL)));

        assertEquals(List.of("B-flag"), primary.flags);     // duplicate's, not the union
    }

    @Test
    void missingPrimaryOrDuplicateIsANoOp() {
        Country a = new Country("A");
        List<Country> pool = new ArrayList<>(List.of(a));

        int merged = Merges.apply(pool, List.of(new Merge("A", "ghost", Merge.MANUAL)));

        assertEquals(0, merged);
        assertEquals(1, pool.size());
    }

    @Test
    void redirectsReferencesToTheSurvivingPrimary() {
        Country primary = new Country("A");
        Country duplicate = new Country("B");
        Holder holder = new Holder("holder", duplicate);
        List<ViewableAdapter> pool = new ArrayList<>(List.of(primary, duplicate, holder));

        Merges.apply(pool, List.of(
                new Merge("Country", "A", "B", Map.of(), Merge.MANUAL)));

        assertSame(primary, holder.country);
        assertFalse(pool.contains(duplicate));
    }

    @Test
    void chainedMergesReachTheFinalPrimaryRegardlessOfDirectiveOrder() {
        Country a = new Country("A");
        Country b = new Country("B");
        Country c = new Country("C");
        b.flags.add("B");
        c.flags.add("C");
        List<Country> pool = new ArrayList<>(List.of(a, b, c));

        Merges.apply(pool, List.of(
                new Merge("Country", "A", "B", Map.of(), Merge.MANUAL),
                new Merge("Country", "B", "C", Map.of(), Merge.MANUAL)));

        assertEquals(List.of(a), pool);
        assertEquals(List.of("B", "C"), a.flags);
    }

    @Test
    void rejectsCyclesBeforeMutatingThePool() {
        Country a = new Country("A");
        Country b = new Country("B");
        List<Country> pool = new ArrayList<>(List.of(a, b));

        assertThrows(IllegalArgumentException.class, () -> Merges.apply(pool, List.of(
                new Merge("Country", "A", "B", Map.of(), Merge.MANUAL),
                new Merge("Country", "B", "A", Map.of(), Merge.MANUAL))));
        assertEquals(List.of(a, b), pool);
    }

    @Test
    void mergeKeepsTheDuplicateSubclassClaim() {
        // A base "State" primary absorbing a reclassified "USState" duplicate must keep the
        // USState claim — class membership is not a FieldSet field. The directive is typed
        // by the BASE class, so it also exercises resolveKey's reclassified-instance fallback.
        WikidataDynamicObject primary = new WikidataDynamicObject("France", "France");
        primary.type("State");
        WikidataDynamicObject duplicate = new WikidataDynamicObject("Francia", "Francia");
        duplicate.type("State");
        duplicate.assignSubclass("USState", "State");   // typeName is now "USState"

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(primary, duplicate));
        int merged = Merges.apply(pool,
                List.of(new Merge("State", "France", "Francia", Map.of(), Merge.MANUAL)));

        assertEquals(1, merged);
        assertFalse(pool.contains(duplicate));
        assertTrue(primary.directClassNames().contains("USState"),
                primary.directClassNames().toString());
    }

    @Test
    void preservesSetCollectionShape() {
        Tagged primary = new Tagged("A");
        Tagged duplicate = new Tagged("B");
        primary.tags.add("a");
        duplicate.tags.add("b");
        List<Tagged> pool = new ArrayList<>(List.of(primary, duplicate));

        Merges.apply(pool, List.of(
                new Merge("Tagged", "A", "B", Map.of(), Merge.MANUAL)));

        assertTrue(primary.tags instanceof TreeSet);
        assertEquals(Set.of("a", "b"), primary.tags);
    }
}
