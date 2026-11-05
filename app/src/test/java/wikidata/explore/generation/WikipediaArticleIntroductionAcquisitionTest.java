package wikidata.explore.generation;

import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceRecipe;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Article prose becomes a field only through its declared, versioned source operation. */
class WikipediaArticleIntroductionAcquisitionTest {

    @Test void aConfiguredIntroductionFillsTheFieldAndKeepsItsEvidence() throws Exception {
        GeneratedProjectModel model = model();
        WikidataDynamicObject episode = episode();

        var result = WikipediaArticleIntroductionAcquisition.apply(
                model, List.of(episode), GenerationLog.NOOP,
                new work.CancellationToken(), null, plan(),
                uri -> response("Judgement of Paris", 17,
                        "Paris is asked to judge three goddesses."));

        assertEquals("Paris is asked to judge three goddesses.", episode.get("summary"));
        assertEquals(1, result.values());
        assertEquals(1, result.pages());
        assertNotNull(episode.articleIntroduction());
        assertEquals("17", episode.articleIntroduction().document().revision());
        assertEquals("https://en.wikipedia.org/wiki/Judgement_of_Paris",
                episode.articleIntroduction().document().url());
        assertFalseBlank(episode.articleIntroduction().document()
                .contentDigest().value());
    }

    @Test void anIntroductionRetainsTheArticleLinkTargetsAndTextRanges() throws Exception {
        GeneratedProjectModel model = model();
        WikidataDynamicObject episode = episode();

        WikipediaArticleIntroductionAcquisition.apply(
                model, List.of(episode), GenerationLog.NOOP,
                new work.CancellationToken(), null, plan(), uri -> response(
                        "Judgement of Paris", 23,
                        "<p><a href=\"/wiki/Achilles\" title=\"Achilles\">Achilles</a> "
                                + "was hidden on Skyros.</p>"));

        assertEquals("Achilles was hidden on Skyros.", episode.get("summary"));
        assertEquals(1, episode.articleIntroduction().links().size());
        var link = episode.articleIntroduction().links().getFirst();
        assertEquals("Achilles", link.text());
        assertEquals("Achilles", link.targetTitle());
        assertEquals("Achilles", episode.articleIntroduction().text()
                .substring(link.start(), link.end()));
    }

    @Test void retainedEvidenceRefillsTheConfiguredFieldWithoutAnotherRequest()
            throws Exception {
        GeneratedProjectModel model = model();
        WikidataDynamicObject episode = episode();
        AtomicInteger requests = new AtomicInteger();

        WikipediaArticleIntroductionAcquisition.apply(
                model, List.of(episode), GenerationLog.NOOP,
                new work.CancellationToken(), null, plan(), uri -> {
                    requests.incrementAndGet();
                    return response("Judgement of Paris", 17, "First introduction.");
                });
        episode.dynamicFields().remove("summary");
        WikipediaArticleIntroductionAcquisition.apply(
                model, List.of(episode), GenerationLog.NOOP,
                new work.CancellationToken(), null, plan(), uri -> {
                    requests.incrementAndGet();
                    throw new AssertionError("retained evidence must avoid the request");
                });

        assertEquals(1, requests.get());
        assertEquals("First introduction.", episode.get("summary"));
    }

    @Test void anArticleWithoutAnIntroductionIsAnAnswerNotARepeatedRequest()
            throws Exception {
        GeneratedProjectModel model = model();
        WikidataDynamicObject episode = episode();
        AtomicInteger requests = new AtomicInteger();

        for (int run = 0; run < 2; run++) {
            WikipediaArticleIntroductionAcquisition.apply(
                    model, List.of(episode), GenerationLog.NOOP,
                    new work.CancellationToken(), null, plan(), uri -> {
                        requests.incrementAndGet();
                        return response("Judgement of Paris", 17, "");
                    });
        }

        assertEquals(1, requests.get());
        assertTrue(episode.articleIntroductionAnswered());
        assertNull(episode.articleIntroduction());
        assertNull(episode.get("summary"));
    }

    @Test void oneRetainedArticleCorrespondenceIsSharedByEveryEntityCopy()
            throws Exception {
        GeneratedProjectModel model = model();
        WikidataDynamicObject first = episode();
        WikidataDynamicObject second = episode();
        second.enwikiTitle("");

        WikipediaArticleIntroductionAcquisition.apply(
                model, List.of(first, second), GenerationLog.NOOP,
                new work.CancellationToken(), null, plan(), uri ->
                        response("Judgement of Paris", 17, "Shared introduction."));

        assertEquals("Judgement of Paris", second.enwikiTitle());
        assertEquals("Shared introduction.", first.get("summary"));
        assertEquals("Shared introduction.", second.get("summary"));
    }

    /** Full introduction, lead paragraph and lead sentence are interpretations of one
     * retained answer, not separate Wikipedia operations or requests. */
    @Test void oneIntroductionSuppliesEveryConfiguredGenericLeadProjection()
            throws Exception {
        GeneratedProjectModel model = model();
        model.rootClass().addField("leadParagraph", FieldType.TEXT,
                FieldCardinality.SINGLE);
        model.rootClass().addField("introduction", FieldType.TEXT,
                FieldCardinality.SINGLE);
        WikidataDynamicObject episode = episode();
        AtomicInteger requests = new AtomicInteger();
        SourceExecutionPlan plan = SourceExecutionPlan.compile(List.of(
                articleText("Episode", "summary", "LEAD_SENTENCE"),
                articleText("Episode", "leadParagraph", "LEAD_PARAGRAPH"),
                articleText("Episode", "introduction", "INTRODUCTION")),
                datasource.Datasources.standard());

        var result = WikipediaArticleIntroductionAcquisition.apply(
                model, List.of(episode), GenerationLog.NOOP,
                new work.CancellationToken(), null, plan, uri -> {
                    requests.incrementAndGet();
                    return response("Judgement of Paris", 17,
                            "<p>Paris judges three goddesses. Athena offers wisdom.</p>"
                                    + "<p>The contest begins at a wedding.</p>");
                });

        assertEquals(1, requests.get());
        assertEquals("Paris judges three goddesses.", episode.get("summary"));
        assertEquals("Paris judges three goddesses. Athena offers wisdom.",
                episode.get("leadParagraph"));
        assertEquals("Paris judges three goddesses. Athena offers wisdom.\n"
                        + "The contest begins at a wedding.",
                episode.get("introduction"));
        assertEquals(3, result.values());
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        episode.addField("summary", FieldType.TEXT, FieldCardinality.SINGLE);
        model.rootClass(episode);
        return model;
    }

    private static SourceExecutionPlan plan() {
        SourceBinding binding = articleText("Episode", "summary", "INTRODUCTION");
        return SourceExecutionPlan.compile(
                List.of(binding), datasource.Datasources.standard());
    }

    private static SourceBinding articleText(
            String type, String field, String projection) {
        return new SourceBinding(
                SourceBindingTarget.fieldValue(type, field,
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikipedia", "article-introduction",
                        Map.of("projection", projection)));
    }

    private static WikidataDynamicObject episode() {
        WikidataDynamicObject episode =
                new WikidataDynamicObject("Q1784308", "Judgement of Paris");
        episode.type("Episode");
        episode.enwikiTitle("Judgement of Paris");
        return episode;
    }

    private static String response(String title, int revision, String extract)
            throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        return json.writeValueAsString(Map.of("query", Map.of("pages", List.of(Map.of(
                "title", title, "lastrevid", revision, "extract", extract)))));
    }

    private static void assertFalseBlank(String value) {
        org.junit.jupiter.api.Assertions.assertFalse(value == null || value.isBlank());
    }
}
