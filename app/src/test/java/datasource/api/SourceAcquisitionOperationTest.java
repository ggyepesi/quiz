package datasource.api;

import datasource.Datasources;
import datasource.EntityRef;
import datasource.api.acquisition.SourceAcquisitionOperation;
import datasource.api.acquisition.SourceAcquisitionRequest;
import org.junit.jupiter.api.Test;
import work.QueryContext;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceAcquisitionOperationTest {

    @Test void explicitPopulationIsAnExecutableOffering() throws Exception {
        @SuppressWarnings("unchecked")
        SourceAcquisitionOperation<List<EntityRef>> seeds =
                (SourceAcquisitionOperation<List<EntityRef>>) Datasources.standard().require(
                        "wikidata", "seed-list", SourceAcquisitionOperation.class);

        List<EntityRef> result = seeds.acquire(new SourceAcquisitionRequest(
                List.of(), Map.of("ids", "Q2, Q1, Q2"))).execute(new QueryContext());

        assertEquals(List.of(EntityRef.wikidata("Q2"), EntityRef.wikidata("Q1")), result);
    }

    @Test void statementPopulationValidatesItsSourceGrammarBeforeRunning() {
        SourceAcquisitionOperation<?> membership = Datasources.standard().require(
                "wikidata", "statement-membership", SourceAcquisitionOperation.class);

        IllegalArgumentException invalidProperty = assertThrows(
                IllegalArgumentException.class,
                () -> membership.acquire(new SourceAcquisitionRequest(List.of(), Map.of(
                        "property", "not-a-pid", "values", "Q5"))));
        assertTrue(invalidProperty.getMessage().contains("property"));

        assertThrows(IllegalArgumentException.class,
                () -> membership.acquire(new SourceAcquisitionRequest(List.of(), Map.of(
                        "property", "P279", "values", "Q5",
                        "includeSubclasses", "true"))));
    }

    @Test void subclassMembershipUsesTheOwnedLabelFreeQueryShape() {
        String sparql = RuleNodeQueryBuilder.subclassMembershipBackboneQuery(
                "P31", List.of("Q5", "Q215627"));

        assertTrue(sparql.contains("SELECT DISTINCT ?value"));
        assertTrue(sparql.contains("VALUES ?root"));
        assertTrue(sparql.contains("wdt:P279*"));
        assertTrue(sparql.contains("?value wdt:P31 ?target"));
        assertFalse(sparql.contains("SERVICE wikibase:label"));
        assertFalse(sparql.contains("http://www.wikidata.org/prop/direct/"));
    }

    @Test void wikipediaDocumentsAreExecutableAndRequirePageReferences() {
        for (String id : List.of("article", "infobox")) {
            SourceAcquisitionOperation<?> operation = Datasources.standard().require(
                    "wikipedia", id, SourceAcquisitionOperation.class);
            assertEquals(SourceValueKind.DOCUMENT, operation.outputSchema().kind());
            assertEquals("wikipedia", operation.inputReferences().getFirst().namespace());
            assertThrows(IllegalArgumentException.class,
                    () -> operation.acquire(new SourceAcquisitionRequest(
                            List.of(), Map.of("wiki", "enwiki"))));
        }
    }
}
