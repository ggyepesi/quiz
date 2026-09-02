package quiz.web;

import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A served collection is addressed by domain AND type.
 *
 * <p>A type name alone used to be the address, and registering a second source for one
 * name replaced the first without a word: Oscars and History both serve {@code Person},
 * History loaded later, and browsing Person returned its 142 people where Oscars' 6,863
 * belonged — under a heading naming Oscars. Refusing the second registration made that
 * visible but left one of them unservable.
 *
 * <p>Neither is right now that domains import shared models. Three domains serve Person
 * because all three import the same Person model; what they do not share is the
 * instances, since a domain owns its own. The domain is the missing half of the address.
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

    @Test void severalDomainsMayServeOneTypeName() {
        ViewableStore store = new ViewableStore();
        store.register(new Fake("Person", List.of()), "oscarnominations");
        store.register(new Fake("Person", List.of()), "History");
        store.register(new Fake("Person", List.of()), "NobelPrizes");

        assertEquals(List.of("Person"), store.types(),
                "one name, which is why it cannot be the whole address");
        assertEquals(
                List.of(new ViewableStore.Address("oscarnominations", "Person"),
                        new ViewableStore.Address("History", "Person"),
                        new ViewableStore.Address("NobelPrizes", "Person")),
                store.addresses());
    }

    /** The bug that started this: each domain's people are its own. */
    @Test void eachDomainServesItsOwnInstancesUnderTheSharedName() throws Exception {
        ViewableStore store = new ViewableStore();
        Item oscarPerson = new Item("Person", "Q1", "Oscar person", null);
        Item historyPerson = new Item("Person", "Q1", "History person", null);
        store.register(new Fake("Person", List.of(oscarPerson)), "oscarnominations");
        store.register(new Fake("Person", List.of(historyPerson)), "History");

        ViewableStore.Address oscars =
                new ViewableStore.Address("oscarnominations", "Person");
        ViewableStore.Address history = new ViewableStore.Address("History", "Person");

        assertEquals(oscarPerson, store.get(oscars, "Q1"));
        assertEquals(historyPerson, store.get(history, "Q1"),
                "same type, same id, different domain — and no load order decides it");
        assertEquals(List.of(oscarPerson), store.list(oscars));
        assertEquals(List.of(historyPerson), store.list(history));
    }

    @Test void oneDomainStillServesEachTypeOnce() {
        ViewableStore store = new ViewableStore();
        store.register(new Fake("Person", List.of()), "History");

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> store.register(new Fake("Person", List.of()), "History"));

        assertTrue(conflict.getMessage().contains("History/Person"),
                conflict.getMessage());
    }

    @Test void registeringTheSameSourceTwiceIsNotAConflict() {
        ViewableStore store = new ViewableStore();
        ViewableSource source = new Fake("Person", List.of());
        store.register(source, "History");
        store.register(source, "History");

        assertEquals(List.of("Person"), store.types());
    }

    @Test void abareNameResolvesWhileItIsUnambiguous() {
        ViewableStore store = new ViewableStore();
        store.register(new Fake("Nomination", List.of()), "oscarnominations");

        assertEquals(new ViewableStore.Address("oscarnominations", "Nomination"),
                store.resolve("Nomination"));
        assertNull(store.resolve("Nothing serves this"));
    }

    /** Asked for a name several domains serve, the store says so rather than choosing. */
    @Test void anAmbiguousNameNamesTheDomainsItCouldMean() {
        ViewableStore store = new ViewableStore();
        store.register(new Fake("Person", List.of()), "oscarnominations");
        store.register(new Fake("Person", List.of()), "History");

        IllegalStateException ambiguous = assertThrows(IllegalStateException.class,
                () -> store.resolve("Person"));

        assertTrue(ambiguous.getMessage().contains("oscarnominations")
                        && ambiguous.getMessage().contains("History"),
                "the caller has to pick, so it is told what the choices are: "
                        + ambiguous.getMessage());
    }

    @Test void differentTypesFromDifferentDomainsCoexist() {
        ViewableStore store = new ViewableStore();
        store.register(new Fake("NobelPrize", List.of()), "NobelPrizes");
        store.register(new Fake("Nomination", List.of()), "oscarnominations");

        assertEquals(List.of("NobelPrize", "Nomination"), store.types());
    }

    /**
     * A value reached through another domain's object is that domain's, so it can no
     * longer occupy the address of a registered source. This used to depend on which
     * type was requested first.
     */
    @Test void aReachedCopyCannotOccupyAnotherDomainsAddress() throws Exception {
        ViewableStore store = new ViewableStore();
        Item oscarPerson = new Item("Person", "Q1", "Oscar person", null);
        Item historyPerson = new Item("Person", "Q1", "History person", null);
        Item holding = new Item("OfficeHolding", "H1", "Holding", historyPerson);
        store.register(new Fake("Person", List.of(oscarPerson)), "oscarnominations");
        store.register(new Fake("OfficeHolding", List.of(holding)), "History");

        assertEquals(List.of(holding),
                store.list(new ViewableStore.Address("History", "OfficeHolding")));

        assertEquals(oscarPerson,
                store.get(new ViewableStore.Address("oscarnominations", "Person"), "Q1"),
                "the Person source owns Person in ITS domain, whatever was loaded first");
    }
}
