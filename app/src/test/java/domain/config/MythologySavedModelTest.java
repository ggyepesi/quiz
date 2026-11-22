package domain.config;

import domain.datasource.api.SourceBindingSlot;
import domain.datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mythology's narrative text is an authored generic Wikipedia source, not Java logic. */
class MythologySavedModelTest {

    @Test void episodesReadTheirSummaryFromTheWikipediaArticleIntroduction()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/mythology/mythology.model.json"));
        GeneratedClassModel episode = model.findClass("Episode");
        GeneratedFieldModel summary = episode.fields().stream()
                .filter(field -> "summary".equals(field.name())).findFirst().orElseThrow();

        assertEquals(FieldType.TEXT, summary.type());
        assertEquals(FieldSourceType.WIKIPEDIA_ARTICLE_INTRODUCTION,
                summary.mapping().sourceType());
        var binding = summary.sourceBindings().stream()
                .filter(value -> value.target().slot()
                        == SourceBindingSlot.PRIMARY_FIELD_VALUE)
                .findFirst().orElseThrow();
        assertEquals("wikipedia", binding.recipe().providerId());
        assertEquals("article-introduction", binding.recipe().operationId());

        var plan = ModelSourceExecutionPlan.synchronizeAndCompile(
                model, domain.datasource.Datasources.standard());
        assertTrue(plan.acquires(domain.datasource.wikipedia.WikipediaDatasourceProvider
                .FAMILY_ARTICLE_INTRODUCTION_FIELD));
        assertNotNull(model.findClass("Character"));
    }

    @Test void episodeCharactersReviewTypedMentionsFromTheRetainedIntroduction()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/mythology/mythology.model.json"));
        GeneratedFieldModel characters = model.findClass("Episode").fields().stream()
                .filter(field -> "characters".equals(field.name()))
                .findFirst().orElseThrow();

        var binding = characters.sourceBindings().stream()
                .filter(value -> value.target().slot()
                        == SourceBindingSlot.CANDIDATE_EVIDENCE)
                .findFirst().orElseThrow();
        assertTrue(domain.datasource.wikipedia.WikipediaDatasourceProvider
                .articleIntroductionMentions(binding));
        assertEquals("Character", characters.entityClassName());
        assertEquals(FieldCardinality.COLLECTION, characters.cardinality());

        var prepared = domain.datasource.Datasources.standard().require(
                binding.recipe().providerId(), binding.recipe().operationId(),
                domain.datasource.api.DatasourceOperation.class).prepare(binding);
        assertEquals(domain.datasource.api.PreparedSourceOperation.Execution.RETAIN,
                prepared.execution(), "mentions remain review work, not generation admission");
    }

    @Test void charactersProposeEpisodesFromBoundedArticlePassages()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/mythology/mythology.model.json"));
        GeneratedClassModel character = model.findClass("Character");
        GeneratedFieldModel narrativeText = character.fields().stream()
                .filter(field -> "narrativeText".equals(field.name()))
                .findFirst().orElseThrow();
        GeneratedFieldModel episodes = character.fields().stream()
                .filter(field -> "episodes".equals(field.name()))
                .findFirst().orElseThrow();
        var narrativeBinding = narrativeText.sourceBindings().stream()
                .filter(value -> value.target().slot()
                        == SourceBindingSlot.CANDIDATE_EVIDENCE)
                .findFirst().orElseThrow();
        var binding = episodes.sourceBindings().stream()
                .filter(value -> value.target().slot()
                        == SourceBindingSlot.CANDIDATE_EVIDENCE)
                .findFirst().orElseThrow();

        assertEquals(FieldType.TEXT, narrativeText.type());
        assertTrue(domain.datasource.wikipedia.WikipediaDatasourceProvider
                .articlePassages(narrativeBinding));
        assertEquals(FieldType.ENTITY, episodes.type());
        assertEquals(FieldCardinality.COLLECTION, episodes.cardinality());
        assertEquals("Episode", episodes.entityClassName());
        var spec = domain.datasource.wikipedia.WikipediaDatasourceProvider
                .articlePassageInstances(binding);
        assertNotNull(spec);
        assertEquals("summary", spec.textField());
        assertEquals("characters", spec.sourceField());
        assertNull(model.findClass("NarrativeWork"));
        assertNull(model.findClass("NarrativeWorkDiscovery"));
        assertTrue(model.entityKindRules().isEmpty());
        assertTrue(GeneratedProjectModelValidator.validate(model).valid(),
                GeneratedProjectModelValidator.validate(model).format());
    }

    @Test void characterChildrenUnionDirectAndQualifiedGroupMembers()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/mythology/mythology.model.json"));
        assertFalse(model.findClass("Character").instanceMapping().requireSitelink(),
                "child routes can retain valid mythological characters without articles");
        assertEquals(6000, model.findClass("Character").instanceMapping().limit(),
                "the wider Character population must remain explicitly bounded");
        GeneratedFieldModel children = model.findClass("Character").fields().stream()
                .filter(field -> "child".equals(field.name()))
                .findFirst().orElseThrow();
        var binding = children.sourceBindings().stream()
                .filter(value -> value.target().slot()
                        == SourceBindingSlot.PRIMARY_FIELD_VALUE)
                .findFirst().orElseThrow();

        assertEquals("wikidata", binding.recipe().providerId());
        assertEquals("relation-routes", binding.recipe().operationId());
        var spec = domain.datasource.wikidata.WikidataRelationRouteSpec.parse(
                binding.recipe().parameter("routes"));
        assertEquals(java.util.List.of(
                        "Direct child: OUTGOING P40",
                        "Child by father: INCOMING P22",
                        "Members of qualified child group: OUTGOING statement:P40, "
                                + "OUTGOING qualifier:P3831, OUTGOING P527"),
                spec.routes().stream().map(route -> route.label() + ": "
                        + route.steps().stream().map(step -> step.direction() + " "
                                + step.relation().relationId())
                        .collect(java.util.stream.Collectors.joining(", "))).toList());
    }

    @Test void savedRouteEvidenceDoesNotReplaceACharactersEntitySource()
            throws Exception {
        var loaded = new domain.runtime.WikidataDynamicObjectJsonStore()
                .loadAllWithFieldGraph(new File(
                        "../data/wikidata/mythology/mythology.snapshot.json"));
        var selected = new java.util.ArrayList<>(loaded.objects().stream()
                .filter(value -> List.of("Q22878", "Q27921410", "Q2666563",
                        "Q21547808").contains(value.getIdentifier()))
                .toList());
        assertEquals(4, selected.size(), "the fixture contains Laocoön and three Acoetes");
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/mythology/mythology.model.json"));

        domain.compile.transform.ProductCompiler.compile(model, selected);

        for (var character : selected) {
            var fields = objectview.field.FieldSet.of(character);
            assertEquals(List.of(new domain.provenance.WikidataSource(
                            character.getIdentifier())),
                    fields.read("wikidataSource"),
                    character.getDisplayName() + " keeps its one QID source");
        }
        var laocoon = selected.stream()
                .filter(value -> "Q22878".equals(value.getIdentifier()))
                .findFirst().orElseThrow();
        assertEquals(5, ((List<?>) objectview.field.FieldSet.of(laocoon)
                .read("wikidataStatements")).size(),
                "route evidence remains separately inspectable");
        var routedAcoetes = selected.stream()
                .filter(value -> "Q27921410".equals(value.getIdentifier()))
                .findFirst().orElseThrow();
        assertEquals(2, ((List<?>) objectview.field.FieldSet.of(routedAcoetes)
                .read("wikidataStatements")).size());
    }

}
