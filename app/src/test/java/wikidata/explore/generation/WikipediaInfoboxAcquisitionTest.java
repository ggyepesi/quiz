package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value read at domain scale must carry the version of the page it was read from.
 *
 * <p>The single-article path built a full {@code SourceDocument}, and category acquisition
 * kept revision and digest for a whole run — but the infobox run asked for {@code rvprop=ids}
 * and then dropped the revid on the floor, writing bare values nothing could revalidate.
 */
class WikipediaInfoboxAcquisitionTest {

    @Test void resolvedBindingDrivesAcquisitionWithoutReadingTheLegacyMapping()
            throws Exception {
        GeneratedProjectModel model = model();
        var field = model.rootClass().fields().getFirst();
        field.mapping().sourceType(FieldSourceType.SPARQL);
        field.mapping().propertyPid("");
        var binding = new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue("Movie", "country",
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new datasource.api.SourceRecipe("wikipedia", "infobox-parameter",
                        java.util.Map.of("property", "Infobox film.country")));
        var sourcePlan = datasource.api.SourceExecutionPlan.compile(
                List.of(binding), datasource.Datasources.standard());
        List<WikidataDynamicObject> objects = films(1);

        var result = WikipediaInfoboxAcquisition.apply(model, objects,
                GenerationLog.NOOP, new work.CancellationToken(), new SitelinkClient(),
                uri -> response("Film 1", 7,
                        "{{Infobox film\n| country = Sierra Leone\n}}"), sourcePlan);

        assertEquals(1, result.values());
        assertEquals("Sierra Leone", objects.getFirst().get("country"));
    }

    @Test void theAcquiredValueKeepsTheRevisionAndDigestOfItsArticle() throws Exception {
        GeneratedProjectModel model = model();
        List<WikidataDynamicObject> objects = films(1);

        var result = WikipediaInfoboxAcquisition.apply(model, objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(), uri -> response(
                        "Film 1", 7, "{{Infobox film\n| country = [[Sierra Leone]]\n}}"));

        assertEquals(1, result.values());
        assertEquals("Sierra Leone", objects.getFirst().get("country"));
        var acquired = objects.getFirst().infoboxParameters();
        assertNotNull(acquired, "the evidence for the value must outlive the request");
        assertEquals("Infobox film", acquired.template());
        assertEquals("7", acquired.document().revision());
        assertEquals("sha256", acquired.document().contentDigest().algorithm());
        assertFalse(acquired.document().contentDigest().value().isBlank());
        assertEquals("https://en.wikipedia.org/wiki/Film_1", acquired.document().url());
    }

    @Test void theDigestFollowsTheParametersAndNotTheProse() throws Exception {
        String first = digestOf("lead paragraph\n{{Infobox film\n| country = Ghana\n}}");
        String rewritten = digestOf("a different lead\n{{Infobox film\n| country = Ghana\n}}");
        String edited = digestOf("lead paragraph\n{{Infobox film\n| country = Togo\n}}");

        assertEquals(first, rewritten, "prose is not the evidence for the value");
        assertNotEquals(first, edited);
    }

    @Test void anArticleWithoutAnInfoboxIsAnsweredSoItIsNotAskedTwice() throws Exception {
        GeneratedProjectModel model = model();
        List<WikidataDynamicObject> objects = films(1);

        WikipediaInfoboxAcquisition.apply(model, objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(),
                uri -> response("Film 1", 7, "a stub with no infobox at all"));

        WikidataDynamicObject film = objects.getFirst();
        assertNull(film.infoboxParameters());
        assertTrue(film.infoboxAnswered(), "'read, and had none' is an answer");
        assertNull(film.get("country"));
    }

    @Test void aSecondRunDoesNotReReadAnArticleAlreadyAnswered() throws Exception {
        GeneratedProjectModel model = model();
        List<WikidataDynamicObject> objects = films(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        for (int run = 0; run < 2; run++) {
            WikipediaInfoboxAcquisition.apply(model, objects, GenerationLog.NOOP,
                    new work.CancellationToken(), new SitelinkClient(), uri -> {
                        calls.incrementAndGet();
                        return response("Film 1", 7, "a stub with no infobox at all");
                    });
        }
        assertEquals(1, calls.get(), "an answered article is not paid for again");
    }

    @Test void everyEligibleInMemoryCopyOfAQidKeepsTheEvidenceAndValue() throws Exception {
        GeneratedProjectModel model = model();
        WikidataDynamicObject first = films(1).getFirst();
        WikidataDynamicObject second = new WikidataDynamicObject("Q1", "Film 1 reference");
        second.type("Movie");

        var result = WikipediaInfoboxAcquisition.apply(model, List.of(first, second),
                GenerationLog.NOOP, new work.CancellationToken(), new SitelinkClient(),
                uri -> response("Film 1", 7, "{{Infobox film|country=Ghana}}"));

        assertEquals("Ghana", first.get("country"));
        assertEquals("Ghana", second.get("country"));
        assertNotNull(first.infoboxParameters());
        assertNotNull(second.infoboxParameters());
        assertEquals(first.infoboxParameters().document(),
                second.infoboxParameters().document());
        assertEquals(1, result.pages());
        assertEquals(1, result.values(),
                "one page supplied one field; how many carriers received it is not a count "
                        + "of what was acquired");
    }

    @Test void aDottedParameterNameIsReadTheWayThePickerShowedIt() throws Exception {
        GeneratedProjectModel model = model("Infobox film.module.runtime");
        List<WikidataDynamicObject> objects = films(1);

        WikipediaInfoboxAcquisition.apply(model, objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(), uri -> response(
                        "Film 1", 7, "{{Infobox film\n| module.runtime = 143 minutes\n}}"));

        assertEquals("143 minutes", objects.getFirst().get("country"),
                "the template is the part before the FIRST dot, everywhere");
    }

    /**
     * The two paths that can read one infobox must agree on WHICH fact they read.
     *
     * <p>Comparing the bulk digest against a hand-built expectation only proved the
     * construction rule was deterministic; it re-spelled the title and URL the test was
     * supposed to be checking, so a path passing different ones still passed. Both paths
     * are run here over the same article instead, and compared by the identity the
     * evidence layer actually uses.
     */
    @Test void bothAcquisitionPathsIdentifyTheSameInfoboxFact() throws Exception {
        String wikitext = "lead prose the two paths need not agree about\n"
                + "{{Infobox film\n| country = [[Sierra Leone]]\n| runtime = 143 minutes\n}}";
        List<WikidataDynamicObject> objects = films(1);

        WikipediaInfoboxAcquisition.apply(model(), objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(),
                uri -> response("Film 1", 7, wikitext));
        var bulk = objects.getFirst().infoboxParameters();

        var single = new wikipedia.WikipediaInfoboxClient(uri -> parseResponse("Film 1", 7,
                wikitext)).byTitle("Film 1").execute(
                        new work.QueryContext());

        assertNotNull(bulk);
        assertNotNull(single);
        assertTrue(bulk.document().sameVersion(single.document()),
                "one infobox read two ways is one version of one document");
        assertEquals(single.document().url(), bulk.document().url());
        assertEquals(single.document().contentDigest(), bulk.document().contentDigest());
        assertEquals(single.parameters(), bulk.parameters());
    }

    @Test void proseTheTwoPathsSeeDifferentlyDoesNotSplitTheIdentity() throws Exception {
        String infobox = "{{Infobox film\n| country = Ghana\n}}";
        List<WikidataDynamicObject> objects = films(1);

        WikipediaInfoboxAcquisition.apply(model(), objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(),
                uri -> response("Film 1", 7, "one lead\n" + infobox));

        var single = new wikipedia.WikipediaInfoboxClient(uri -> parseResponse("Film 1", 7,
                "a rewritten lead\n" + infobox + "\nand a new section")).byTitle("Film 1")
                .execute(new work.QueryContext());

        assertEquals(single.document().contentDigest(),
                objects.getFirst().infoboxParameters().document().contentDigest(),
                "the digest follows the parameters, so prose cannot fork the identity");
    }

    /** The single-page shape: action=parse, which answers with the whole wikitext. */
    private static String parseResponse(String title, int revid, String wikitext) {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        return "{\"parse\":{\"title\":" + json.valueToTree(title) + ",\"revid\":" + revid
                + ",\"wikitext\":" + json.valueToTree(wikitext) + "}}";
    }

    /** #106: the parameter is selectable as a field's OWN source, not only as a fallback
     *  for one Wikidata could not fill. The acquisition already read the primary mapping;
     *  nothing proved it, and the editor around it was wired for the fallback alone. */
    @Test void aFieldWhosePrimarySourceIsAnInfoboxParameterIsFilledFromIt() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        var country = movie.addField("country", FieldType.TEXT, FieldCardinality.SINGLE);
        country.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        country.mapping().propertyPid("Infobox film.country");
        model.rootClass(movie);
        List<WikidataDynamicObject> objects = films(1);

        var result = WikipediaInfoboxAcquisition.apply(model, objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(),
                uri -> response("Film 1", 7, "{{Infobox film|country=Sierra Leone}}"));

        assertEquals(1, result.values());
        assertEquals("Sierra Leone", objects.getFirst().get("country"));
        assertEquals("7", objects.getFirst().infoboxParameters().document().revision());
    }

    private static String digestOf(String wikitext) throws Exception {
        List<WikidataDynamicObject> objects = films(1);
        WikipediaInfoboxAcquisition.apply(model(), objects, GenerationLog.NOOP,
                new work.CancellationToken(), new SitelinkClient(),
                uri -> response("Film 1", 7, wikitext));
        return objects.getFirst().infoboxParameters().document().contentDigest().value();
    }

    private static void assertNotEquals(String unexpected, String actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual);
    }

    private static GeneratedProjectModel model() {
        return model("Infobox film.country");
    }

    private static GeneratedProjectModel model(String key) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        var country = movie.addField("country", FieldType.TEXT, FieldCardinality.SINGLE);
        var fallback = country.ensureFallbackMapping();
        fallback.sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        fallback.propertyPid(key);
        model.rootClass(movie);
        return model;
    }

    private static List<WikidataDynamicObject> films(int count) {
        List<WikidataDynamicObject> objects = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            WikidataDynamicObject object = new WikidataDynamicObject("Q" + i, "Film " + i);
            object.type("Movie");
            objects.add(object);
        }
        return objects;
    }

    private static String response(String title, int revid, String wikitext) {
        return "{\"query\":{\"pages\":[{\"title\":\"" + title + "\",\"revisions\":[{\"revid\":"
                + revid + ",\"slots\":{\"main\":{\"content\":"
                + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(wikitext)
                + "}}}]}]}}";
    }

    /** The article title comes from the entity documents the run already fetches. */
    private static final class SitelinkClient extends wikidata.api.WikidataApiClient {
        SitelinkClient() { super(DEFAULT_USER_AGENT); }
        @Override protected com.fasterxml.jackson.databind.JsonNode getEntitiesBatch(
                List<String> qids, boolean withClaims) throws Exception {
            StringBuilder entities = new StringBuilder();
            for (String id : qids) {
                if (!entities.isEmpty()) entities.append(',');
                entities.append('"').append(id).append("\":{\"id\":\"").append(id)
                        .append("\",\"sitelinks\":{\"enwiki\":{\"title\":\"Film ")
                        .append(id.substring(1)).append("\"}}}");
            }
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("{\"entities\":{" + entities + "}}");
        }
    }
}
