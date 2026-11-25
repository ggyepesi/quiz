package domain.build;

import domain.config.FieldCardinality;
import domain.config.FieldProductionKind;
import domain.config.FieldSourceType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.ModelSourceExecutionPlan;
import domain.config.OwnedFieldSources;
import domain.datasource.Datasources;
import domain.datasource.api.SourceRuntimeServices;
import domain.datasource.schema.FieldType;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import org.junit.jupiter.api.Test;
import wikidata.api.WikidataApiClient;
import work.CancellationToken;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bound the work to selected records while the same Enrich family resolves the sources. */
class SelectedFieldAcquisitionTest {
    @Test void sharedAndContextualInfoboxBindingsUseTheEnrichLoader() throws Exception {
        var project = new GeneratedProjectModel();
        var work = new GeneratedClassModel("Work");
        project.rootClass(work);
        var details = new GeneratedClassModel("Details");
        details.ownedClass(true);
        project.addClass(details);
        var sharedSite = work.addField("details", FieldType.ENTITY, FieldCardinality.SINGLE);
        var overrideSite = work.addField("otherDetails", FieldType.ENTITY, FieldCardinality.SINGLE);
        for (var site : List.of(sharedSite, overrideSite)) {
            site.entityClassName("Details");
            site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        }
        var caption = details.addField("caption", FieldType.TEXT, FieldCardinality.SINGLE);
        caption.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        caption.mapping().propertyPid("Infobox film.country");
        var override = OwnedFieldSources.ensureOverride(overrideSite, "caption");
        override.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        override.mapping().propertyPid("Infobox film.language");
        var unrelated = details.addField("duration", FieldType.TEXT, FieldCardinality.SINGLE);
        unrelated.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        unrelated.mapping().propertyPid("Infobox film.runtime");
        var plan = ModelSourceExecutionPlan.synchronizeAndCompile(project, Datasources.standard());
        var selected = plan.fieldValues("Details", "caption");
        assertEquals(2, selected.steps().size());
        assertSame(plan.steps().stream().filter(step -> step.target().contextual())
                .findFirst().orElseThrow(), selected.steps().stream()
                .filter(step -> step.target().contextual()).findFirst().orElseThrow());
        var shared = part("Q1", "Film 1", "details");
        var contextual = part("Q1", "Film 1", "otherDetails");
        var unselected = part("Q2", "Film 2", "otherDetails");
        var calls = new ArrayList<URI>();
        var runtime = services(uri -> {
            calls.add(uri);
            return article("Film 1", "country = Sierra Leone\n| language = English\n| runtime = 95");
        });

        var result = SelectedFieldAcquisition.load(project, selected, "caption",
                List.of(shared, contextual), List.of(shared, contextual, unselected),
                runtime, GenerationLog.NOOP, new CancellationToken());

        assertEquals(List.of("Sierra Leone", "English"), result.reads().stream()
                .map(SelectedFieldAcquisition.Read::value).toList());
        assertEquals(List.of("Infobox film.country", "Infobox film.language"), result.reads().stream()
                .map(read -> read.source().propertyId()).toList());
        assertEquals(1, calls.size());
        assertTrue(URLDecoder.decode(calls.getFirst().getRawQuery(), StandardCharsets.UTF_8)
                .endsWith("&titles=Film 1"), calls.getFirst().toString());
        for (var member : List.of(shared, contextual, unselected)) assertNull(member.get("caption"));

        var enrichPool = List.of(shared.copyWithoutFields(), contextual.copyWithoutFields());
        ExternalSourceAcquisition.apply(project, enrichPool, plan, runtime,
                GenerationLog.NOOP, new CancellationToken(), ExternalSourceAcquisition.FailurePolicy.STRICT);
        assertEquals(result.reads().stream().map(SelectedFieldAcquisition.Read::value).toList(),
                enrichPool.stream().map(member -> member.get("caption")).toList());
        assertEquals("95", enrichPool.getFirst().get("duration"));
    }

    @Test void examinedEmptyAndProviderFailureRemainDifferent() throws Exception {
        var project = new GeneratedProjectModel();
        var work = new GeneratedClassModel("Work");
        var caption = work.addField("caption", FieldType.TEXT, FieldCardinality.SINGLE);
        caption.mapping().sourceType(FieldSourceType.WIKIPEDIA_INFOBOX);
        caption.mapping().propertyPid("Rendered.Country");
        project.rootClass(work);
        var plan = ModelSourceExecutionPlan.synchronizeAndCompile(project, Datasources.standard())
                .fieldValues("Work", "caption");
        var answered = movie("Q1", "Film 1");
        var failed = movie("Q2", "Film 2");
        var runtime = services(uri -> {
            if (uri.getQuery().contains("Film_2") || uri.getQuery().contains("Film+2")
                    || uri.getQuery().contains("Film 2")) {
                throw new java.io.IOException("No response");
            }
            return """
                    {"parse":{"title":"Film 1","pageid":1,"revid":7,
                      "text":"<table class='infobox'><tr><th>Language</th><td>English</td></tr></table>"}}
                    """;
        });
        var result = SelectedFieldAcquisition.load(project, plan, "caption",
                List.of(answered, failed), List.of(answered, failed), runtime,
                GenerationLog.NOOP, new CancellationToken());
        assertNull(result.reads().getFirst().value());
        assertTrue(result.reads().getFirst().answered());
        assertFalse(result.reads().getFirst().failed());
        assertNull(result.reads().getLast().value());
        assertFalse(result.reads().getLast().answered());
        assertTrue(result.reads().getLast().failed());
        assertFalse(result.failures().isEmpty());
        assertFalse(answered.renderedInfoboxAnswered());
        assertFalse(failed.renderedInfoboxAnswered());
    }

    /** Reported: after changing a field's source and pressing Load, members that already
     *  had a value kept it. The staged copies carried the current value, and Enrich's
     *  loaders are additive (a populated field is left alone), so only empty members were
     *  read. The read now starts empty and review compares it with the current value. */
    @Test void aMemberThatAlreadyHasAValueIsReadAgain() throws Exception {
        var project = new GeneratedProjectModel();
        var episode = new GeneratedClassModel("Episode");
        var summary = episode.addField("summary", FieldType.TEXT, FieldCardinality.SINGLE);
        summary.mapping().sourceType(FieldSourceType.WIKIPEDIA_ARTICLE_INTRODUCTION);
        project.rootClass(episode);
        var plan = ModelSourceExecutionPlan.synchronizeAndCompile(project, Datasources.standard())
                .fieldValues("Episode", "summary");
        var member = new WikidataDynamicObject("Q1784308", "Judgement of Paris");
        member.type("Episode");
        member.enwikiTitle("Judgement of Paris");
        member.put("summary", "An older summary.");
        WikipediaArticleIntroductionAcquisition.Fetcher fetcher = uri ->
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                        "query", java.util.Map.of("pages", List.of(java.util.Map.of(
                                "title", "Judgement of Paris", "lastrevid", 17,
                                "extract", "Paris is asked to judge three goddesses.")))));
        var runtime = SourceRuntimeServices.builder()
                .put("wikidata", WikidataApiClient.class, new WikidataApiClient("test"))
                .put("wikipedia", WikipediaArticleIntroductionAcquisition.Fetcher.class, fetcher)
                .build();

        var result = SelectedFieldAcquisition.load(project, plan, "summary", List.of(member),
                List.of(member), runtime, GenerationLog.NOOP, new CancellationToken());

        var read = result.reads().getFirst();
        assertEquals("Paris is asked to judge three goddesses.", read.value());
        org.junit.jupiter.api.Assertions.assertNotNull(read.source(),
                "a re-read value carries its source");
        assertEquals("An older summary.", member.get("summary"), "the member is not written");
        assertEquals(List.of(), result.failures());
    }

    private static SourceRuntimeServices services(WikipediaInfoboxAcquisition.Fetcher fetcher) {
        return SourceRuntimeServices.builder()
                .put("wikidata", WikidataApiClient.class, new WikidataApiClient("test"))
                .put("wikipedia", WikipediaInfoboxAcquisition.Fetcher.class, fetcher).build();
    }

    private static WikidataDynamicObject movie(String qid, String title) {
        var member = new WikidataDynamicObject(qid, title);
        member.type("Work");
        member.enwikiTitle(title);
        return member;
    }

    private static WikidataDynamicObject part(String qid, String title, String site) {
        var member = movie(qid, title);
        member.type("Details");
        member.directClasses(List.of("Details"));
        member.part(true);
        member.typeKey("Details@Work." + site);
        return member;
    }

    private static String article(String title, String fields) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "query", java.util.Map.of("pages", List.of(java.util.Map.of(
                        "title", title, "pageid", 1,
                        "revisions", List.of(java.util.Map.of("revid", 7, "slots",
                                java.util.Map.of("main", java.util.Map.of("content",
                                        "{{Infobox film\n| " + fields + "\n}}")))))))));
    }
}
