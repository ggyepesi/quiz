package quiz.curation;

import objectview.annotations.Reference;
import org.junit.jupiter.api.Test;
import quiz.QuizableAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergesTest {

    /** A State-shaped fixture: identity name + two image lists, one populated on each
     *  of the primary/duplicate so the merge must union them (the Tanzania case). */
    @SuppressWarnings("unused")
    static class Country extends QuizableAdapter {
        private final String name;
        @Reference private final List<String> flags = new ArrayList<>();
        @Reference private final List<String> shapes = new ArrayList<>();

        Country(String name) { this.name = name; }
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
}
