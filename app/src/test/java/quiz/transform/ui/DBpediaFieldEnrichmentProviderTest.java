package quiz.transform.ui;

import datasource.enrichment.EnrichmentProposal;
import quiz.enrichment.EnrichmentRequest;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void convertsNumericPopulationLiteralToANumberForAnOrderedField() {
        objectview.field.FieldRef population = objectview.field.FieldRef.described(
                "population", objectview.field.FieldKind.ORDERED,
                objectview.field.FieldKind.ORDERED, "Ordered",
                false, false, null, false, false,
                false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "france", "Q142", "France"),
                "population", false, List.of(), population);

        assertEquals(68_373_433L,
                     DBpediaFieldEnrichmentProvider.typedValue(request, "68373433"));
    }

    @Test
    void leavesNonNumericOrderedLiteralUnchanged() {
        objectview.field.FieldRef date = objectview.field.FieldRef.described(
                "date", objectview.field.FieldKind.ORDERED,
                objectview.field.FieldKind.ORDERED, "Ordered",
                false, false, null, false, false,
                false, false, "", false);
        EnrichmentRequest request = new EnrichmentRequest(
                new EnrichmentProposal.Subject("Country", "france", "Q142", "France"),
                "date", false, List.of(), date);

        assertEquals("2026-08-06",
                     DBpediaFieldEnrichmentProvider.typedValue(request, "2026-08-06"));
    }
}
