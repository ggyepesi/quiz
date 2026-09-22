package wikidata.explore.generation;

import datasource.wikidata.WikidataDatasourceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikidataComputedFieldAcquisitionTest {

    @Test void sitelinkCountReadsTheStoredWikidataMeasureForABoundedBatch() {
        String query = WikidataComputedFieldAcquisition.query(List.of("Q1", "Q2"),
                new WikidataDatasourceProvider.ComputedFieldSpec(
                        WikidataDatasourceProvider.ComputedFieldSpec.Kind.SITELINKS, ""));

        assertTrue(query.contains("VALUES ?entity { wd:Q1 wd:Q2 }"), query);
        assertTrue(query.contains("?entity wikibase:sitelinks ?sitelinks"), query);
        assertTrue(query.contains("COALESCE(?sitelinks, 0)"), query);
    }

    @Test void holderCountCountsDistinctIncomingP39SubjectsAndKeepsZeroes() {
        String query = WikidataComputedFieldAcquisition.query(List.of("Q11696"),
                new WikidataDatasourceProvider.ComputedFieldSpec(
                        WikidataDatasourceProvider.ComputedFieldSpec.Kind.INCOMING_RELATION,
                        "P39"));

        assertTrue(query.contains("OPTIONAL { ?source wdt:P39 ?entity . }"), query);
        assertTrue(query.contains("COUNT(DISTINCT ?source)"), query);
        assertTrue(query.contains("GROUP BY ?entity"), query);
    }

    @Test void aFailedComputedFieldDoesNotPreventTheNextFieldFromRunning()
            throws Exception {
        var model = new wikidata.explore.model.GeneratedProjectModel();
        var position = new wikidata.explore.model.GeneratedClassModel("Position");
        position.addField("sitelinkCount", datasource.schema.FieldType.NUMBER,
                wikidata.explore.model.FieldCardinality.SINGLE);
        position.addField("holderCount", datasource.schema.FieldType.NUMBER,
                wikidata.explore.model.FieldCardinality.SINGLE);
        model.rootClass(position);
        var bindings = List.of(
                new datasource.api.SourceBinding(
                        datasource.api.SourceBindingTarget.fieldValue("Position",
                                "sitelinkCount",
                                datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                        new datasource.api.SourceRecipe("wikidata", "sitelink-count",
                                Map.of())),
                new datasource.api.SourceBinding(
                        datasource.api.SourceBindingTarget.fieldValue("Position",
                                "holderCount",
                                datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                        new datasource.api.SourceRecipe("wikidata",
                                "incoming-relation-count", Map.of("property", "P39"))));
        var plan = datasource.api.SourceExecutionPlan.compile(
                bindings, datasource.Datasources.standard());
        var object = new wikidata.explore.extract.WikidataDynamicObject("Q1", "one");
        object.type("Position");

        try (var client = new wikidata.WikidataSparqlClient("test") {
            @Override public List<wikidata.WikidataBinding> query(String sparql) {
                if (sparql.contains("wikibase:sitelinks")) {
                    throw new IllegalStateException("sitelinks unavailable");
                }
                return List.of(new wikidata.WikidataBinding(Map.of(
                        "entity", "http://www.wikidata.org/entity/Q1", "count", "7")));
            }
        }) {
            assertThrows(java.io.IOException.class,
                    () -> WikidataComputedFieldAcquisition.apply(model, List.of(object),
                            plan, client, wikidata.explore.extract.GenerationLog.NOOP));
        }
        assertEquals(7L, object.get("holderCount"),
                "holderCount must run even when the preceding sitelink field fails");
    }

    @Test void batchesUseTheSparqlClientsConfiguredParallelism() throws Exception {
        var model = new wikidata.explore.model.GeneratedProjectModel();
        var position = new wikidata.explore.model.GeneratedClassModel("Position");
        position.addField("holderCount", datasource.schema.FieldType.NUMBER,
                wikidata.explore.model.FieldCardinality.SINGLE);
        model.rootClass(position);
        var binding = new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue("Position", "holderCount",
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new datasource.api.SourceRecipe("wikidata", "incoming-relation-count",
                        Map.of("property", "P39")));
        var plan = datasource.api.SourceExecutionPlan.compile(
                List.of(binding), datasource.Datasources.standard());
        List<wikidata.explore.extract.WikidataDynamicObject> objects =
                new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            var object = new wikidata.explore.extract.WikidataDynamicObject("Q" + i, "Q" + i);
            object.type("Position");
            objects.add(object);
        }
        var entered = new java.util.concurrent.CountDownLatch(2);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();

        try (var client = new wikidata.WikidataSparqlClient("test", 2) {
            @Override public List<wikidata.WikidataBinding> query(String sparql)
                    throws Exception {
                int now = active.incrementAndGet();
                maximum.accumulateAndGet(now, Math::max);
                entered.countDown();
                entered.await(2, java.util.concurrent.TimeUnit.SECONDS);
                active.decrementAndGet();
                return List.of();
            }
        }) {
            WikidataComputedFieldAcquisition.apply(model, objects, plan, client,
                    wikidata.explore.extract.GenerationLog.NOOP);
        }
        assertEquals(2, maximum.get(), "the two 50-QID batches should overlap");
    }
}
