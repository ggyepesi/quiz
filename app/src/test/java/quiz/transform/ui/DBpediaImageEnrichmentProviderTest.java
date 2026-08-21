package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import datasource.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DBpediaImageEnrichmentProviderTest {

    @Test
    void mapsResolvedResourcesToSelectableIdentitiesAndAttributedMedia() {
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Person", "421", "George D. Snell"),
                "image", false, List.of());
        List<DBpediaLookup.ImageHit> hits = List.of(
                new DBpediaLookup.ImageHit(
                        "http://dbpedia.org/resource/George_Davis_Snell",
                        "https://example.test/snell.jpg"));

        EnrichmentProposal result =
                DBpediaImageEnrichmentProvider.proposal(request, hits, false);

        assertEquals(1, result.identities().size());
        assertEquals("George Davis Snell", result.identities().get(0).canonicalName());
        assertEquals(List.of("George D. Snell"), result.identities().get(0).aliases());
        assertEquals(result.identities().get(0).candidateId(),
                result.media().get(0).identityCandidateId());
        assertEquals("DBpedia", result.media().get(0).source().kind());
        assertTrue(result.media().get(0).discoveryMethod().contains("depiction"));
    }
}
