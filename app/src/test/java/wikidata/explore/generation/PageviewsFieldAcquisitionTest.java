package wikidata.explore.generation;

import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceRecipe;
import datasource.pageviews.PageviewsDatasourceProvider;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Article views become a field only through the configured Pageviews operation. */
class PageviewsFieldAcquisitionTest {

    /** 14 November 2026: the last complete month is October 2026. */
    private static final Clock NOVEMBER = Clock.fixed(
            Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC);

    @Test void viewsOverTheConfiguredCompleteMonthsFillTheField() throws Exception {
        WikidataDynamicObject anubis = deity("Q47534", "Anubis");
        List<URI> requested = new ArrayList<>();

        var result = PageviewsFieldAcquisition.apply(model(), List.of(anubis),
                GenerationLog.NOOP, new work.CancellationToken(), null, plan("3"),
                new datasource.pageviews.PageviewsClient(uri -> {
                    requested.add(uri);
                    return months(100, 200, 300);
                }), NOVEMBER);

        assertEquals(600L, anubis.get("views"));
        assertEquals(1, result.values());
        assertEquals(List.of(URI.create("https://wikimedia.org/api/rest_v1/metrics/"
                + "pageviews/per-article/en.wikipedia/all-access/user/Anubis/monthly/"
                + "2026080100/2026103100")), requested);
    }

    @Test void anArticleTheServiceHasNoRecordOfGetsNoValueNotZero() throws Exception {
        WikidataDynamicObject anubis = deity("Q47534", "Anubis");

        var result = PageviewsFieldAcquisition.apply(model(), List.of(anubis),
                GenerationLog.NOOP, new work.CancellationToken(), null, plan("12"),
                new datasource.pageviews.PageviewsClient(uri -> null), NOVEMBER);

        assertNull(anubis.get("views"));
        assertEquals(0, result.values());
    }

    @Test void theArticleTitleIsEncodedAsOnePathSegment() throws Exception {
        WikidataDynamicObject ra = deity("Q12236", "Ra (deity)/Sun");
        List<URI> requested = new ArrayList<>();

        PageviewsFieldAcquisition.apply(model(), List.of(ra), GenerationLog.NOOP,
                new work.CancellationToken(), null, plan("1"),
                new datasource.pageviews.PageviewsClient(uri -> {
                    requested.add(uri);
                    return months(5);
                }), NOVEMBER);

        assertEquals("/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/"
                        + "all-access/user/Ra_%28deity%29%2FSun/monthly/"
                        + "2026100100/2026103100",
                requested.getFirst().getRawPath());
    }

    @Test void anArticleTheServiceKeepsFailingOnDoesNotCostTheOthersTheirValues() {
        WikidataDynamicObject anubis = deity("Q47534", "Anubis");
        WikidataDynamicObject ra = deity("Q12236", "Ra");
        List<String> attempts = new ArrayList<>();

        java.io.IOException unread = assertThrows(java.io.IOException.class, () ->
                PageviewsFieldAcquisition.apply(model(), List.of(anubis, ra),
                        GenerationLog.NOOP, new work.CancellationToken(), null, plan("1"),
                        new datasource.pageviews.PageviewsClient(uri -> {
                            String path = uri.getRawPath();
                            attempts.add(path);
                            if (path.contains("/Anubis/")) {
                                throw new objectview.utils.UrlOpener.HttpStatusException(
                                        500, uri.toURL());
                            }
                            return months(7);
                        }), NOVEMBER));

        assertEquals(7L, ra.get("views"));
        assertNull(anubis.get("views"));
        assertTrue(unread.getMessage().contains("Anubis (Q47534)"), unread.getMessage());
        assertTrue(attempts.stream().filter(path -> path.contains("/Anubis/")).count() > 1,
                "a 500 is worth another attempt: " + attempts);
    }

    @Test void aWindowOutsideTheServiceRangeIsRefusedWhenThePlanIsCompiled() {
        assertThrows(IllegalArgumentException.class, () -> plan("0"));
        assertThrows(IllegalArgumentException.class, () -> plan("twelve"));
    }

    private static GeneratedProjectModel model() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel deity = new GeneratedClassModel("Deity");
        deity.addField("views", FieldType.NUMBER, FieldCardinality.SINGLE);
        model.rootClass(deity);
        return model;
    }

    private static SourceExecutionPlan plan(String months) {
        SourceBinding binding = new SourceBinding(
                SourceBindingTarget.fieldValue("Deity", "views",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe(PageviewsDatasourceProvider.ID,
                        PageviewsDatasourceProvider.ARTICLE_VIEWS,
                        Map.of(PageviewsDatasourceProvider.MONTHS, months)));
        return SourceExecutionPlan.compile(
                List.of(binding), datasource.Datasources.standard());
    }

    private static WikidataDynamicObject deity(String qid, String title) {
        WikidataDynamicObject deity = new WikidataDynamicObject(qid, title);
        deity.type("Deity");
        deity.enwikiTitle(title);
        return deity;
    }

    private static String months(long... views) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (long value : views) items.add(Map.of("views", value));
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("items", items));
    }
}
