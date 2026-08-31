package quiz.web;

import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A served type name is what a URL asks for and what the client lists, so it resolves to
 * ONE source. Registering a second used to replace the first without a word: Oscars and
 * History both declare a served {@code Person}, History loads later, and browsing Person
 * returned its 142 people where Oscars' 6,863 belonged — under a heading naming Oscars.
 */
class ViewableStoreTypeConflictTest {

    private record Fake(String type, List<Viewable> items) implements ViewableSource {
        @Override public Collection<? extends Viewable> load() { return items; }
    }

    private static final class Item extends ViewableAdapter {
        private final String type;
        private final String id;
        private final String label;
        private final Viewable related;

        private Item(String type, String id, String label, Viewable related) {
            this.type = type;
            this.id = id;
            this.label = label;
            this.related = related;
        }

        @Override public String typeName() { return type; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return label; }
    }

    @Test void twoSourcesCannotServeOneTypeName() {
        ViewableStore store = new ViewableStore();
        store.register(new Fake("Person", List.of()), "oscarnominations");

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> store.register(new Fake("Person", List.of()), "History"));

        assertTrue(conflict.getMessage().contains("oscarnominations")
                        && conflict.getMessage().contains("History"),
                "the conflict names both claimants, or it cannot be acted on: "
                        + conflict.getMessage());
        assertTrue(conflict.getMessage().contains("Person"));
    }

    @Test void theFirstClaimantKeepsTheType() throws Exception {
        ViewableStore store = new ViewableStore();
        ViewableSource first = new Fake("Person", List.of());
        store.register(first, "oscarnominations");
        try {
            store.register(new Fake("Person", List.of()), "History");
        } catch (IllegalStateException expected) {
            // the point of the next assertion
        }

        assertEquals(List.of("Person"), store.types(),
                "a refused registration leaves the store as it was, not half-changed");
    }

    @Test void registeringTheSameSourceTwiceIsNotAConflict() {
        ViewableStore store = new ViewableStore();
        ViewableSource source = new Fake("Person", List.of());
        store.register(source, "History");
        store.register(source, "History");

        assertEquals(List.of("Person"), store.types());
    }

    @Test void differentTypesFromDifferentDomainsCoexist() {
        ViewableStore store = new ViewableStore();
        store.register(new Fake("NobelPrize", List.of()), "NobelPrizes");
        store.register(new Fake("Nomination", List.of()), "oscarnominations");

        assertEquals(List.of("NobelPrize", "Nomination"), store.types());
    }

    @Test void aRegisteredTypeOutranksACopyReachedFromAnotherDataset()
            throws Exception {
        ViewableStore store = new ViewableStore();
        Item oscarPerson = new Item("Person", "Q1", "Oscar person", null);
        Item historyPerson = new Item("Person", "Q1", "History person", null);
        Item holding = new Item("OfficeHolding", "H1", "Holding", historyPerson);
        store.register(new Fake("Person", List.of(oscarPerson)), "oscarnominations");
        store.register(new Fake("OfficeHolding", List.of(holding)), "History");

        // Loading the holding first used to let its reachable Person occupy Q1.
        assertEquals(List.of(holding), store.list("OfficeHolding"));

        assertEquals(oscarPerson, store.get("Person", "Q1"),
                "the source registered for Person owns Person addresses regardless of "
                        + "which other dataset was requested first");
    }
}
