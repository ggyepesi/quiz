package domain.build;

import domain.datasource.wikidata.WikidataDatasourceProvider;
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

    @Test void inheritedHolderCountCountsDistinctHoldersOfStrictSubclasses() {
        String query = WikidataComputedFieldAcquisition.inheritedQuery(
                List.of("Q11696"),
                new WikidataDatasourceProvider.ComputedFieldSpec(
                        WikidataDatasourceProvider.ComputedFieldSpec.Kind
                                .INHERITED_INCOMING_RELATION,
                        "P39"));

        assertTrue(query.contains("VALUES ?descendant { wd:Q11696 }"), query);
        assertTrue(query.contains("?source wdt:P39 ?descendant"), query);
        assertTrue(query.contains("?descendant wdt:P279+ ?entity"), query);
        assertTrue(query.contains("SELECT DISTINCT ?descendant ?entity ?source"), query);
        assertTrue(query.contains("} UNION {"),
                "holder and ancestor edges must not be multiplied at WDQS: " + query);
        assertTrue(query.contains("hint:Query hint:optimizer \"None\""), query);
        assertTrue(!query.contains("VALUES ?entity"),
                "the endpoint must start from the bounded loaded descendants, not scan "
                        + "all holdings toward target ancestors");
    }

    @Test void inheritedHolderCountJoinsEdgesLocallyAndCountsSharedHolderOnce()
            throws Exception {
        var model = new domain.config.GeneratedProjectModel();
        var position = new domain.config.GeneratedClassModel("Position");
        position.addField("inheritedHolderCount", domain.datasource.schema.FieldType.NUMBER,
                domain.config.FieldCardinality.SINGLE);
        model.rootClass(position);
        var binding = new domain.datasource.api.SourceBinding(
                domain.datasource.api.SourceBindingTarget.fieldValue("Position",
                        "inheritedHolderCount",
                        domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new domain.datasource.api.SourceRecipe("wikidata",
                        "inherited-incoming-relation-count", Map.of("property", "P39")));
        var plan = domain.datasource.api.SourceExecutionPlan.compile(
                List.of(binding), domain.datasource.Datasources.standard());
        var first = position("Q101");
        var second = position("Q102");
        var ancestor = position("Q200");

        try (var client = new wikidata.WikidataSparqlClient("test") {
            @Override public List<wikidata.WikidataBinding> query(String sparql) {
                return List.of(
                        row("Q101", null, "Q900"),
                        row("Q101", "Q200", null),
                        row("Q102", null, "Q900"),
                        row("Q102", null, "Q901"),
                        row("Q102", "Q200", null));
            }
        }) {
            WikidataComputedFieldAcquisition.apply(model,
                    List.of(first, second, ancestor), plan, client,
                    domain.runtime.GenerationLog.NOOP);
        }

        assertEquals(2L, ancestor.get("inheritedHolderCount"),
                "one holder occupying two descendants must count once at the ancestor");
        assertEquals(0L, first.get("inheritedHolderCount"));
        assertEquals(0L, second.get("inheritedHolderCount"));
    }

    @Test void aFailedComputedFieldDoesNotPreventTheNextFieldFromRunning()
            throws Exception {
        var model = new domain.config.GeneratedProjectModel();
        var position = new domain.config.GeneratedClassModel("Position");
        position.addField("sitelinkCount", domain.datasource.schema.FieldType.NUMBER,
                domain.config.FieldCardinality.SINGLE);
        position.addField("holderCount", domain.datasource.schema.FieldType.NUMBER,
                domain.config.FieldCardinality.SINGLE);
        model.rootClass(position);
        var bindings = List.of(
                new domain.datasource.api.SourceBinding(
                        domain.datasource.api.SourceBindingTarget.fieldValue("Position",
                                "sitelinkCount",
                                domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                        new domain.datasource.api.SourceRecipe("wikidata", "sitelink-count",
                                Map.of())),
                new domain.datasource.api.SourceBinding(
                        domain.datasource.api.SourceBindingTarget.fieldValue("Position",
                                "holderCount",
                                domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                        new domain.datasource.api.SourceRecipe("wikidata",
                                "incoming-relation-count", Map.of("property", "P39"))));
        var plan = domain.datasource.api.SourceExecutionPlan.compile(
                bindings, domain.datasource.Datasources.standard());
        var object = new domain.runtime.WikidataDynamicObject("Q1", "one");
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
                            plan, client, domain.runtime.GenerationLog.NOOP));
        }
        assertEquals(7L, object.get("holderCount"),
                "holderCount must run even when the preceding sitelink field fails");
    }

    @Test void batchesUseTheSparqlClientsConfiguredParallelism() throws Exception {
        var model = new domain.config.GeneratedProjectModel();
        var position = new domain.config.GeneratedClassModel("Position");
        position.addField("holderCount", domain.datasource.schema.FieldType.NUMBER,
                domain.config.FieldCardinality.SINGLE);
        model.rootClass(position);
        var binding = new domain.datasource.api.SourceBinding(
                domain.datasource.api.SourceBindingTarget.fieldValue("Position", "holderCount",
                        domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new domain.datasource.api.SourceRecipe("wikidata", "incoming-relation-count",
                        Map.of("property", "P39")));
        var plan = domain.datasource.api.SourceExecutionPlan.compile(
                List.of(binding), domain.datasource.Datasources.standard());
        List<domain.runtime.WikidataDynamicObject> objects =
                new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            var object = new domain.runtime.WikidataDynamicObject("Q" + i, "Q" + i);
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
                    domain.runtime.GenerationLog.NOOP);
        }
        assertEquals(2, maximum.get(), "the two 50-QID batches should overlap");
    }

    private static domain.runtime.WikidataDynamicObject position(String qid) {
        var object = new domain.runtime.WikidataDynamicObject(qid, qid);
        object.type("Position");
        return object;
    }

    private static wikidata.WikidataBinding row(
            String descendant, String ancestor, String source) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("descendant", "http://www.wikidata.org/entity/" + descendant);
        if (ancestor != null) {
            values.put("entity", "http://www.wikidata.org/entity/" + ancestor);
        }
        if (source != null) {
            values.put("source", "http://www.wikidata.org/entity/" + source);
        }
        return new wikidata.WikidataBinding(values);
    }
}
