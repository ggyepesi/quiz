package quiz.web;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Quiz endpoints receive the address shown by the domain-aware selector, not a bare
 * class name. Passing {@code History:Person} into the store's bare-name lookup found
 * no source, so every ABCD quiz and pairing made from the new selector was empty.
 */
class DomainAddressedQuizTest {

    @Test void aDomainQualifiedClassProducesAbcdQuestions() throws Exception {
        Fixture fixture = fixture();

        Quiz quiz = QuizGenerator.generate(
                fixture.store(), fixture.address(), "",
                List.of(objectview.field.ViewableContractFieldSet.DISPLAY_KEY),
                List.of("offices"), 4);

        assertEquals("History:Person", quiz.type());
        assertEquals(4, quiz.questions().size());
        assertFalse(quiz.questions().getFirst().options().isEmpty());
    }

    @Test void pairingUsesTheSameDomainQualifiedClass() throws Exception {
        Fixture fixture = fixture();

        Pairing pairing = PairingGenerator.generate(
                fixture.store(), fixture.address(), "",
                List.of(objectview.field.ViewableContractFieldSet.DISPLAY_KEY),
                List.of("offices"), 4);

        assertEquals("History:Person", pairing.type());
        assertEquals(4, pairing.pairs().size());
    }

    private static Fixture fixture() {
        List<Viewable> people = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            WikidataDynamicObject office = new WikidataDynamicObject("P" + i, "Office " + i);
            office.type("Position");
            WikidataDynamicObject person = new WikidataDynamicObject("Q" + i, "Person " + i);
            person.type("Person");
            person.put("offices", List.of(office));
            people.add(person);
        }

        ViewableStore store = new ViewableStore();
        store.register(new ViewableSource() {
            @Override public String type() { return "Person"; }
            @Override public Collection<? extends Viewable> load() { return people; }
        }, "History");
        return new Fixture(store, new ViewableStore.Address("History", "Person"));
    }

    private record Fixture(ViewableStore store, ViewableStore.Address address) {}
}
