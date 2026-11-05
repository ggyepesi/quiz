package wikidata.explore.model;

import datasource.api.SourceBindingSlot;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.io.File;

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
                model, datasource.Datasources.standard());
        assertTrue(plan.acquires(datasource.wikipedia.WikipediaDatasourceProvider
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
        assertTrue(datasource.wikipedia.WikipediaDatasourceProvider
                .articleIntroductionMentions(binding));
        assertEquals("Character", characters.entityClassName());
        assertEquals(FieldCardinality.COLLECTION, characters.cardinality());

        var prepared = datasource.Datasources.standard().require(
                binding.recipe().providerId(), binding.recipe().operationId(),
                datasource.api.DatasourceOperation.class).prepare(binding);
        assertEquals(datasource.api.PreparedSourceOperation.Execution.RETAIN,
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
        assertTrue(datasource.wikipedia.WikipediaDatasourceProvider
                .articlePassages(narrativeBinding));
        assertEquals(FieldType.ENTITY, episodes.type());
        assertEquals(FieldCardinality.COLLECTION, episodes.cardinality());
        assertEquals("Episode", episodes.entityClassName());
        var spec = datasource.wikipedia.WikipediaDatasourceProvider
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

}
