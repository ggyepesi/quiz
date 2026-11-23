package domain.config;

import domain.datasource.api.SourceBindingSlot;
import domain.datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import domain.runtime.RuleTreeExtractor;
import domain.build.CompiledPipelineRun;
import domain.build.PipelineRequest;
import domain.compile.rule.RuleTreeCompiler;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forces the first Egyptian-deity configuration to stay bounded and source-authored. */
class EgyptianGodsSavedModelTest {

    @Test void savedAssessorsReuseLoadedDeitiesInsteadOfCreatingDuplicateRows()
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/egyptiangods/egyptiangods.model.json"));
        var loaded = new domain.runtime.WikidataDynamicObjectJsonStore()
                .loadAllWithFieldGraph(new File(
                        "../data/wikidata/egyptiangods/egyptiangods.snapshot.json"));
        var curation = new domain.curation.ManualCuration(new File(
                "../data/wikidata/egyptiangods/egyptiangods.curation.json")).load();
        var pool = new ArrayList<>(loaded.objects());
        domain.curation.CuratedInstances.apply(pool, curation.instances());
        domain.curation.Corrections.apply(pool, java.util.List.of(curation));

        domain.DomainModel compiled =
                domain.compile.transform.ProductCompiler.compile(model, pool);
        domain.runtime.WikidataDynamicObject group = compiled.instances().stream()
                .map(domain.runtime.WikidataDynamicObject.class::cast)
                .filter(value -> "Q9133532".equals(value.getIdentifier()))
                .findFirst().orElseThrow();
        java.util.List<?> members = (java.util.List<?>) group.get("members");
        objectview.Viewable nehebkau = compiled.instances().stream()
                .filter(value -> "Q2044276".equals(value.getIdentifier()))
                .findFirst().orElseThrow();

        assertEquals(42, members.size());
        assertEquals(42, members.stream().map(value ->
                        ((objectview.Viewable) value).typeName() + '\0'
                                + ((objectview.Viewable) value).getIdentifier())
                .distinct().count());
        assertSame(nehebkau, members.stream()
                .filter(value -> "Q2044276".equals(
                        ((objectview.Viewable) value).getIdentifier()))
                .findFirst().orElseThrow(),
                "Nehebkau must be the loaded EgyptianDeity, not a row-shaped duplicate");
        assertEquals(1, compiled.instances().stream()
                .filter(value -> "Nehebkau".equals(value.getDisplayName())).count());
    }

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
        assertNull(fields.get("iconographicSymbol").wikipediaCategoryRule(),
                "animal-category evidence is not an iconographic symbol");
        GeneratedFieldModel associatedAnimal = fields.get("associatedAnimal");
        assertEquals(FieldType.ENTITY, associatedAnimal.type());
        assertTrue(associatedAnimal.collection());
        assertTrue(associatedAnimal.unclassedEntity());
        assertTrue(associatedAnimal.mapping().propertyPid().isBlank(),
                "the association comes from the authored Wikipedia category source");
        WikipediaCategoryRule animalAssociations =
                associatedAnimal.wikipediaCategoryRule();
        assertEquals(23, animalAssociations.mappings().size());
        assertTrue(animalAssociations.mappings().contains(
                new WikipediaCategoryMapping("Falcon deities", "Q43489")));
        assertTrue(animalAssociations.mappings().contains(
                new WikipediaCategoryMapping("Cattle deities", "Q830")));
        assertTrue(animalAssociations.mappings().contains(
                new WikipediaCategoryMapping("Scorpions in culture", "Q19125")));
        var categorySource = FieldSourceBindings.binding(
                associatedAnimal, SourceBindingSlot.CATEGORY_EVIDENCE);
        assertEquals("wikipedia", categorySource.recipe().providerId());
        assertEquals("category", categorySource.recipe().operationId());
        GeneratedFieldModel symbolizedBy = fields.get("symbolizedBy");
        assertEquals(FieldType.ENTITY, symbolizedBy.type());
        assertTrue(symbolizedBy.collection());
        assertTrue(symbolizedBy.unclassedEntity());
        assertEquals("P4878", symbolizedBy.mapping().propertyPid());
        assertEquals("symbolizes", symbolizedBy.mapping().propertyLabel());
        assertEquals(RuleDirection.ITEM_TO_ROOT, symbolizedBy.mapping().direction());
        var symbolizesSource = FieldSourceBindings.binding(
                symbolizedBy, SourceBindingSlot.PRIMARY_FIELD_VALUE);
        assertEquals("wikidata", symbolizesSource.recipe().providerId());
        assertEquals("property-value", symbolizesSource.recipe().operationId());
        assertEquals("P1049", fields.get("worshippedBy").mapping().propertyPid());
        assertEquals(FieldType.TEXT, fields.get("summary").type());
        assertEquals(FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE,
                fields.get("summary").mapping().sourceType());
        var summarySource = FieldSourceBindings.binding(
                fields.get("summary"), SourceBindingSlot.PRIMARY_FIELD_VALUE);
        assertEquals("wikipedia", summarySource.recipe().providerId());
        assertEquals("article-introduction", summarySource.recipe().operationId());
        assertEquals("LEAD_SENTENCE", summarySource.recipe().parameter("projection"));
        GeneratedFieldModel symbol = fields.get("symbol");
        assertEquals(FieldType.TEXT, symbol.type());
        assertEquals(FieldSourceType.WIKIPEDIA_INFOBOX, symbol.mapping().sourceType());
        assertEquals("Rendered.Symbol", symbol.mapping().propertyPid());
        var symbolSource = FieldSourceBindings.binding(
                symbol, SourceBindingSlot.PRIMARY_FIELD_VALUE);
        assertEquals("wikipedia", symbolSource.recipe().providerId());
        assertEquals("infobox-parameter", symbolSource.recipe().operationId());
        assertEquals("Rendered.Symbol", symbolSource.recipe().parameter("property"));
        for (String relation : java.util.List.of("domain", "iconographicSymbol",
                "associatedAnimal", "symbolizedBy",
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
        assertTrue(preview.contains("Member field \"symbolizedBy\"")
                && preview.contains("wdt:P4878"), preview);
        assertTrue(preview.contains("schema:about"), preview);

        GeneratedClassModel deityGroup = model.findClass("DeityGroup");
        assertEquals("DeityGroupMember", deity.baseClassName());
        assertEquals("DeityGroupMember",
                model.findClass("AssessorOfMaat").baseClassName());
        GeneratedFieldModel members = deityGroup.fields().stream()
                .filter(value -> value.name().equals("members"))
                .findFirst().orElseThrow();
        var tableEvidence = FieldSourceBindings.binding(
                members, SourceBindingSlot.CANDIDATE_EVIDENCE);
        var tableSpec = domain.datasource.wikipedia.WikipediaDatasourceProvider
                .articleTableRowInstances(tableEvidence);
        assertEquals("Description > List of names, provenances and tasks (Wilkinson et al)",
                tableSpec.sectionPath());
        assertEquals("", tableSpec.table());
        assertEquals("Name of the deity", tableSpec.displayColumn());
        assertEquals("AssessorOfMaat", tableSpec.targetClass());
        assertEquals("rowDetails", tableSpec.detailsField());
        assertEquals("groups", tableSpec.sourceField());
        assertEquals("deities", tableSpec.referencesField());
    }
}
