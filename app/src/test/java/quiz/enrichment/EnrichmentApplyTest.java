package quiz.enrichment;

import objectview.Viewable;
import objectview.field.DynamicFields;
import objectview.field.FieldSet;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ModelBuilder enrich pass applies accepted values in memory to the matching generated
 *  (dynamic) instance, and leaves others untouched. */
class EnrichmentApplyTest {

    /** A minimal generated-style dynamic instance. */
    private static final class Dyn implements Viewable, DynamicFields {
        private final String id;
        private final Map<String, Object> map = new LinkedHashMap<>();
        Dyn(String id) { this.id = id; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
        @Override public FieldSet fields() { return FieldSet.of(this); }
        @Override public Map<String, Object> dynamicFieldValues() { return map; }
    }

    private static EnrichmentDecision decision(String targetId, String field, Object value) {
        EnrichmentProposal.Subject subject =
                new EnrichmentProposal.Subject("Country", targetId, "Q1", targetId);
        EnrichmentProposal.SourceRef source =
                new EnrichmentProposal.SourceRef("Wikidata", "Q1", "url");
        EnrichmentProposal.FieldCandidate candidate = new EnrichmentProposal.FieldCandidate(
                "cand", "id", field, null, value, source,
                EnrichmentProposal.ReviewAction.FILL_IF_EMPTY);
        EnrichmentProposal.IdentityCandidate identity = new EnrichmentProposal.IdentityCandidate(
                "id", targetId, List.of(), "", source, 1.0, List.of());
        return new EnrichmentDecision(subject, identity,
                List.of(new EnrichmentDecision.FieldDecision(candidate, candidate.suggestedAction())),
                null);
    }

    @Test
    void writesAcceptedValuesToTheMatchingDynamicInstanceOnly() {
        Dyn france = new Dyn("france");
        Dyn spain = new Dyn("spain");

        int applied = EnrichmentApply.toDynamicInstances(
                List.of(france, spain),
                List.of(decision("france", "population", 68_000_000L)));

        assertEquals(1, applied);
        assertEquals(68_000_000L, france.dynamicFieldValues().get("population"));
        assertTrue(spain.dynamicFieldValues().isEmpty(), "unrelated instance untouched");
    }

    @Test
    void skipsADecisionWithNoMatchingInstance() {
        Dyn france = new Dyn("france");

        int applied = EnrichmentApply.toDynamicInstances(
                List.of(france),
                List.of(decision("germany", "population", 83_000_000L)));

        assertEquals(0, applied);
        assertTrue(france.dynamicFieldValues().isEmpty());
    }
}
