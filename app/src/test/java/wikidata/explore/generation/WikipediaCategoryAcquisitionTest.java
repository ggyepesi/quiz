package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaCategoryAcquisitionTest {
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

        var result = WikipediaCategoryAcquisition.apply(model, objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(), uri -> {
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
