package wikidata.explore.model;

import datasource.api.SourceBindingSlot;
import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.RuleTreeExtractor;
import wikidata.explore.generation.CompiledPipelineRun;
import wikidata.explore.generation.PipelineRequest;
import wikidata.explore.rule.RuleTreeCompiler;

import java.io.File;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forces the first Egyptian-deity configuration to stay bounded and source-authored. */
class EgyptianGodsSavedModelTest {

    @Test void savedDomainLoadsWithItsPopulationRelationsAndWikipediaSource()
            throws Exception {
        File file = new File("../data/wikidata/egyptiangods/egyptiangods.model.json");
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(file);
        GeneratedClassModel deity = model.findClass("EgyptianDeity");

        assertEquals("Egyptian Gods", model.name());
        assertEquals(GeneratedProjectModel.ProjectKind.DOMAIN, model.projectKind());
        assertEquals(EntityBound.Kind.RELATION, deity.membership().kind());
        assertEquals("P31", deity.membership().relationPid());
        assertEquals(java.util.List.of("Q146083"), deity.membership().qids());
        assertTrue(deity.membership().includeDescendants());
        assertTrue(deity.instanceMapping().requireSitelink());
        assertEquals(1000, deity.instanceMapping().limit());

        Map<String, GeneratedFieldModel> fields = deity.fields().stream()
                .collect(Collectors.toMap(GeneratedFieldModel::name, value -> value));
        assertEquals(FieldType.IMAGE, fields.get("image").type());
        assertEquals("P2925", fields.get("domain").mapping().propertyPid());
        assertEquals("P4185", fields.get("iconographicSymbol").mapping().propertyPid());
        assertEquals("P1049", fields.get("worshippedBy").mapping().propertyPid());
        for (String relation : java.util.List.of("domain", "iconographicSymbol",
                "father", "mother", "spouse", "child", "sibling", "partOf",
                "equivalent", "worshippedBy")) {
            assertTrue(fields.get(relation).unclassedEntity(), relation);
        }

        var article = deity.sourceBindings().stream()
                .filter(binding -> binding.target().slot()
                        == SourceBindingSlot.ARTICLE_CORRESPONDENCE)
                .findFirst().orElseThrow();
        assertEquals("wikidata", article.recipe().providerId());
        assertEquals("sitelink", article.recipe().operationId());
        assertEquals("enwiki", article.recipe().parameter("wiki"));
        assertTrue(GeneratedProjectModelValidator.validateForAcquisition(model).valid(),
                GeneratedProjectModelValidator.validateForAcquisition(model).format());
        assertFalse(CompiledPipelineRun.compile(
                PipelineRequest.generateDomain(model)).blocked());
        String preview = String.join("\n", new RuleTreeExtractor(null).previewQueries(
                RuleTreeCompiler.compileClass(deity, model), deity.generationDepth()));
        assertTrue(preview.contains("wdt:P31"), preview);
        assertTrue(preview.contains("wdt:P279*"), preview);
        assertTrue(preview.contains("wd:Q146083"), preview);
        assertTrue(preview.contains("schema:about"), preview);
    }
}
