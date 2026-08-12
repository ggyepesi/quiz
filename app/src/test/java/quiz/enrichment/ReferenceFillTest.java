package quiz.enrichment;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.CorrectionSource;
import quiz.curation.Corrections;
import quiz.transform.DynamicViewable;
import quiz.transform.ui.DomainReferenceResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entity-valued property can fill a reference field.
 *
 * <p>The claim was always fetched correctly — it yields a QID — but a reference field
 * needs an instance, and nothing bridged the two. Every candidate was therefore marked
 * schema-incompatible and {@code Movies.locations} reported nothing found however it was
 * configured.
 *
 * <p>Three things have to hold together: an already-pooled target is LINKED (identity
 * stays single), an absent one is CREATED with its name, and the decision survives as a
 * QID that resolves back to the pooled instance.
 */
class ReferenceFillTest {

    @Test void anAlreadyPooledTargetIsLinkedRatherThanDuplicated() {
        DynamicViewable pooled = new DynamicViewable("Q60", "New York City");
        DomainReferenceResolver resolver =
                new DomainReferenceResolver(List.of(pooled));

        ReferenceResolver.Resolved resolved =
                resolver.resolve("Q60", "New York City", "Location");

        assertSame(pooled, resolved.value(),
                   "a second object with the same QID would split the entity in two");
        assertFalse(resolved.created());
        assertTrue(resolver.created().isEmpty());
    }

    @Test void anAbsentTargetIsCreatedCarryingItsName() {
        DomainReferenceResolver resolver = new DomainReferenceResolver(List.of());

        ReferenceResolver.Resolved resolved =
                resolver.resolve("Q1297", "Chicago", "Location");

        assertTrue(resolved.created(), "review must be able to say it is ADDING this one");
        assertEquals("Chicago", resolved.value().getDisplayName(),
                     "created named by its QID would be a fresh unnamed reference");
        assertEquals("Q1297", resolved.value().getIdentifier());
        assertEquals("Location", resolved.value().typeName(),
                     "stamped as the class the field expects, not left untyped");
        assertEquals(List.of("Q1297"), List.copyOf(resolver.created().keySet()));
    }

    /** Resolving the same QID twice must yield one instance — otherwise two films
     *  curated in the same pass would each invent their own Chicago. */
    @Test void thesameQidResolvesToOneInstanceWithinASession() {
        DomainReferenceResolver resolver = new DomainReferenceResolver(List.of());

        Viewable first = resolver.resolve("Q1297", "Chicago", "Location").value();
        ReferenceResolver.Resolved second = resolver.resolve("Q1297", "Chicago", "Location");

        assertSame(first, second.value());
        assertFalse(second.created(), "the second is a link to the first, not a new one");
    }

    /** The decision persists as a QID, and comes back as the POOLED instance — not a
     *  copy of it, or the pool would end up with two objects for one entity. */
    @Test void aStoredReferenceResolvesBackToThePooledInstance() {
        DynamicViewable film = new DynamicViewable("Q1054", "12 Monkeys");
        film.type("Movies");
        DynamicViewable place = new DynamicViewable("Q1297", "Chicago");
        place.type("Location");

        Corrections.apply(List.of(film, place), List.of(source(new Correction(
                "Movies", "Q1054", "locations", "Q1297", "wikidata",
                Correction.REFERENCE_COLLECTION, CorrectionPolicy.ADD_TO_COLLECTION, null))));

        assertEquals(List.of(place), film.get("locations"));
        assertSame(place, ((List<?>) film.get("locations")).get(0));
    }

    /**
     * A QID the pool does not contain still lands, as a stub named by its QID. Dropping
     * the value would lose a decision that was made and recorded; a stub is visible, and
     * the UNNAMED_REFERENCE scope lists it for a label fill.
     */
    @Test void aReferenceToSomethingNotPooledLandsAsANamedGapNotALostValue() {
        DynamicViewable film = new DynamicViewable("Q1054", "12 Monkeys");
        film.type("Movies");

        Corrections.apply(List.of(film), List.of(source(new Correction(
                "Movies", "Q1054", "locations", "Q999999", "wikidata",
                Correction.REFERENCE_COLLECTION, CorrectionPolicy.ADD_TO_COLLECTION, null))));

        List<?> values = (List<?>) film.get("locations");
        assertEquals(1, values.size(), "the decision is not silently dropped");
        assertEquals("Q999999", ((Viewable) values.get(0)).getIdentifier());
    }

    private static CorrectionSource source(Correction correction) {
        return () -> List.of(correction);
    }
}
