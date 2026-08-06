package quiz.transform.ui;

import quiz.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentRequest;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DBpedia field provider serves only DBpedia-sourced fields (the mirror of the Wikidata
 *  provider serving only SPARQL), and needs the subject's QID to join by owl:sameAs. */
class DBpediaFieldEnrichmentProviderTest {

    private static FieldSourceMapping dbpediaSource(String property) {
        FieldSourceMapping source = new FieldSourceMapping();
        source.sourceType(FieldSourceType.DBPEDIA);
        source.propertyPid(property);
        return source;
    }

    private static EnrichmentRequest request(String qid) {
        return new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "france", qid, "France"),
                "officialName", false, List.of());
    }

    @Test
    void supportsADBpediaSourcedFieldWithAQid() {
        assertTrue(new DBpediaFieldEnrichmentProvider(dbpediaSource("longName"))
                .supports(request("Q142")));
    }

    @Test
    void rejectsANonDBpediaSource() {
        FieldSourceMapping sparql = new FieldSourceMapping();
        sparql.sourceType(FieldSourceType.SPARQL);
        sparql.propertyPid("P1448");
        assertFalse(new DBpediaFieldEnrichmentProvider(sparql).supports(request("Q142")));
    }

    @Test
    void rejectsWhenTheSubjectHasNoQid() {
        assertFalse(new DBpediaFieldEnrichmentProvider(dbpediaSource("longName"))
                .supports(request(null)));
    }
}
