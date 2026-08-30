package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaCategoryAcquisitionTest {
    @Test void categoryAcquisitionIsEnabledByTheResolvedBinding() {
        var binding = new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue(
                        "Movie", "location",
                        datasource.api.SourceBindingSlot.CATEGORY_EVIDENCE),
                new datasource.api.SourceRecipe(
                        datasource.wikipedia.WikipediaDatasourceProvider.ID,
                        datasource.wikipedia.WikipediaCategoryDiscoveryOperation.ID,
                        java.util.Map.of("pattern", "Films set in <value>")));
        var plan = datasource.api.SourceExecutionPlan.compile(
                List.of(binding), datasource.Datasources.standard());

        assertTrue(WikipediaCategoryAcquisition.configured(plan));
        assertEquals("REVIEW", datasource.wikipedia.WikipediaDatasourceProvider
                .categoryRule(binding).policy());
    }

    /**
     * A pattern still being typed matches nothing. It used to be admitted, and on the
     * Enrich path — which acquires before it compiles — that bought a whole pool's
     * sitelinks and category pages before the validator refused the model. Exactly one
     * placeholder, which is what the validator requires, so the two agree rather than
     * nearly agreeing.
     */
    @Test void aPatternWithoutExactlyOnePlaceholderNamesNoAcquisition() {
        assertNull(rule("Films set in"), "no placeholder — nothing to substitute");
        assertNull(rule("Films set in <value> in <value>"), "two — the validator refuses it");
        assertEquals("Films set in <value>", rule("Films set in <value>").pattern());
        assertFalse(WikipediaCategoryAcquisition.configured(planFor("Films set in")),
                "and a run does not fetch for it");
        assertTrue(WikipediaCategoryAcquisition.configured(planFor("Films set in <value>")));
    }

    private static datasource.wikipedia.WikipediaDatasourceProvider.CategoryRule rule(
            String pattern) {
        return datasource.wikipedia.WikipediaDatasourceProvider.categoryRule(
                bindingFor(pattern));
    }

    private static datasource.api.SourceBinding bindingFor(String pattern) {
        return new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue(
                        "Movie", "location",
                        datasource.api.SourceBindingSlot.CATEGORY_EVIDENCE),
                new datasource.api.SourceRecipe(
                        datasource.wikipedia.WikipediaDatasourceProvider.ID,
                        datasource.wikipedia.WikipediaCategoryDiscoveryOperation.ID,
                        java.util.Map.of("pattern", pattern)));
    }

    private static datasource.api.SourceExecutionPlan planFor(String pattern) {
        return datasource.api.SourceExecutionPlan.compile(
                List.of(bindingFor(pattern)), datasource.Datasources.standard());
    }

    @Test void batchesFiftyEntitiesAndFollowsCategoryContinuation() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        var location = movie.addField("location", FieldType.ENTITY,
                FieldCardinality.COLLECTION);
        location.entityClassName("Location");
        location.ensureWikipediaCategoryRule().pattern("Films set in <value>");
        model.rootClass(movie);
        List<WikidataDynamicObject> objects = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            WikidataDynamicObject object = new WikidataDynamicObject("Q" + i, "Film " + i);
            object.type("Movie"); objects.add(object);
        }
        AtomicInteger wikidataCalls = new AtomicInteger();
        AtomicInteger wikipediaCalls = new AtomicInteger();

        // The article title comes from the SAME entity documents the run fetches, so the
        // client is the one that answers it — and answers it from its store the second
        // time an entity is asked about.
        class SitelinkClient extends wikidata.api.WikidataApiClient {
            SitelinkClient() { super(DEFAULT_USER_AGENT); }
            @Override protected com.fasterxml.jackson.databind.JsonNode getEntitiesBatch(
                    List<String> qids, boolean withClaims) throws Exception {
                wikidataCalls.incrementAndGet();
                StringBuilder entities = new StringBuilder();
                for (String id : qids) {
                    if (!entities.isEmpty()) entities.append(',');
                    entities.append('"').append(id)
                            .append("\":{\"id\":\"").append(id)
                            .append("\",\"sitelinks\":{\"enwiki\":{\"title\":\"Film ")
                            .append(id.substring(1)).append("\"}}}");
                }
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree("{\"entities\":{" + entities + "}}");
            }
        }

        datasource.api.SourceExecutionPlan sourcePlan =
                wikidata.explore.model.ModelSourceExecutionPlan.compile(
                        model, datasource.Datasources.standard());
        var result = WikipediaCategoryAcquisition.apply(objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(), sourcePlan, uri -> {
                    wikipediaCalls.incrementAndGet();
                    boolean continued = uri.getRawQuery().contains("clcontinue=");
                    String continuation = continued ? ""
                            : ",\"continue\":{\"clcontinue\":\"next\"}";
                    String category = continued ? "Drama films" : "Films set in Sierra Leone";
                    return "{\"query\":{\"pages\":[{\"title\":\"Film 1\","
                            + "\"revisions\":[{\"revid\":7}],\"categories\":[{\"title\":\"Category:"
                            + category + "\"}]}]}" + continuation + "}";
                });

        assertEquals(2, wikidataCalls.get(), "51 QIDs use two 50-QID entity batches");
        assertTrue(wikipediaCalls.get() >= 3,
                "the first title batch continues and the second batch is still fetched");
        assertEquals(2, result.batches());
        assertEquals("Films set in Sierra Leone",
                objects.getFirst().categoryMemberships().getFirst().category());
    }

    private static String decodedParameter(String query, String name) {
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        return "";
    }
}
