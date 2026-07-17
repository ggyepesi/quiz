package quiz.web.sources;

import org.junit.jupiter.api.Test;
import quiz.Quizable;
import quiz.transform.DynamicQuizable;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.web.QuizableStore;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic DomainModelSource serves a DomainModel's instances by type through
 * the web store — and the store indexes a DYNAMIC reference (map-held), so the
 * client can resolve an expanded reference.
 */
class DomainModelSourceTest {

    @Test void servesByTypeAndIndexesDynamicReferences() throws Exception {
        DynamicQuizable country = new DynamicQuizable("C1", "Wonderland");
        country.type("Country");

        DynamicQuizable city = new DynamicQuizable("Q1", "Metropolis");
        city.type("City");
        city.put("country", country);   // a reference held in the property map

        DomainModel domain = new InMemory(List.of(city, country));

        QuizableStore store = new QuizableStore();
        DomainModelSource.register(store, domain);

        assertTrue(store.types().contains("City"));
        assertTrue(store.types().contains("Country"));

        Collection<Quizable> cities = store.list("City");
        assertEquals(1, cities.size());
        assertEquals("Metropolis", cities.iterator().next().getDisplayName());

        // The referenced Country was indexed via the dynamic map (not a Java field).
        Quizable resolved = store.get("Country", "C1");
        assertNotNull(resolved);
        assertEquals("Wonderland", resolved.getDisplayName());
    }

    /** Minimal in-memory DomainModel for the test. */
    private static final class InMemory implements DomainModel {
        private final List<? extends Quizable> items;
        InMemory(List<? extends Quizable> items) { this.items = items; }
        @Override public List<String> types() {
            return items.stream().map(Quizable::typeName).distinct().toList();
        }
        @Override public List<DomainField> fields(String type) { return List.of(); }
        @Override public Collection<? extends Quizable> instances() { return items; }
        @Override public Class<? extends Quizable> universe() { return Quizable.class; }
    }
}
