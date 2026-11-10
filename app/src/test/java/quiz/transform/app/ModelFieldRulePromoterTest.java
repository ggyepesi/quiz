package quiz.transform.app;

import wikidata.explore.extract.SnapshotDomain;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.ManualCuration;
import quiz.curation.FieldSourceRecipe;
import quiz.curation.ValueSource;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelFieldRulePromoterTest {

    @TempDir Path temp;

    @Test void promotesOneSourcedFieldWithoutConsumingItsCuration() throws Exception {
        File modelFile = temp.resolve("states.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("States");
        model.rootClass(new GeneratedClassModel("State"));
        new GeneratedProjectModelStore().save(model, modelFile);

        WikidataDynamicObject state = new WikidataDynamicObject("Q1", "One");
        state.type("State");
        state.put("population", 10L);
        SnapshotDomain base = new SnapshotDomain(List.of(state));
        ManualCuration curation = new ManualCuration(
                temp.resolve("states.curation.json").toFile());
        Correction correction = new Correction(
                "State", "Q1", "population", 10L, "wikidata", null,
                CorrectionPolicy.FILL_IF_EMPTY,
                new ValueSource("Wikidata", "Q1", "P1082",
                        "population", "ROOT_TO_ITEM",
                        "https://www.wikidata.org/wiki/Q1"));
        curation.put("State", "Q1", "population", 10L, "wikidata", null,
                correction.policy(), correction.source());
        curation.save();
        CuratableDomain domain = new CuratableDomain(
                base, curation, base.memberRoots(), List.of(), modelFile);

        var preview = domain.previewPromotion(correction);
        assertTrue(preview.eligible(), preview.reason());
        assertTrue(preview.addsField());
        domain.promote(correction);
        assertNull(new GeneratedProjectModelStore().load(modelFile).findClass("State")
                        .fields().stream().filter(f -> "population".equals(f.name()))
                        .findFirst().orElse(null),
                "promotion changes the working model; only Save writes the file (#305)");
        domain.writeProjectModel();

        var loaded = new GeneratedProjectModelStore().load(modelFile);
        var promoted = loaded.findClass("State").fields().stream()
                .filter(field -> "population".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals("P1082", promoted.mapping().propertyPid());
        assertEquals("population", promoted.mapping().propertyLabel());
        assertEquals(wikidata.explore.model.RuleDirection.ROOT_TO_ITEM,
                promoted.mapping().direction());
        assertEquals(1, curation.corrections().size(),
                "promotion must retain the reviewed per-instance correction");
        assertFalse(domain.previewPromotion(correction).eligible(),
                "the same model rule is not offered twice");
    }

    @Test void refusesAValueWithoutReusablePropertyEvidence() throws Exception {
        File modelFile = temp.resolve("states.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("State"));
        new GeneratedProjectModelStore().save(model, modelFile);
        WikidataDynamicObject state = new WikidataDynamicObject("Q1", "One");
        state.type("State");
        state.put("population", 10L);
        SnapshotDomain base = new SnapshotDomain(List.of(state));
        CuratableDomain domain = new CuratableDomain(base,
                new ManualCuration(temp.resolve("x.curation.json").toFile()),
                base.memberRoots(), List.of(), modelFile);
        Correction manual = new Correction(
                "State", "Q1", "population", 10L, "manual", null);

        assertFalse(domain.previewPromotion(manual).eligible());
        assertTrue(domain.previewPromotion(manual).reason().contains("source"));
    }

    @Test void promotesCurationCategoryRecipeWithoutRemovingTheOverride() throws Exception {
        File modelFile = temp.resolve("movies.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movies");
        movie.addField("locations", datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        model.rootClass(movie);
        new GeneratedProjectModelStore().save(model, modelFile);
        WikidataDynamicObject instance = new WikidataDynamicObject("Q1", "Movie");
        instance.type("Movies");
        SnapshotDomain base = new SnapshotDomain(List.of(instance));
        ManualCuration curation = new ManualCuration(temp.resolve("movies.curation.json").toFile());
        FieldSourceRecipe recipe = FieldSourceRecipe.wikipediaCategory(
                "Movies", "locations", "Films set in <value>",
                wikidata.explore.model.CategoryCandidatePolicy.REVIEW);
        curation.putSourceRecipe(recipe);
        CuratableDomain domain = new CuratableDomain(
                base, curation, base.memberRoots(), List.of(), modelFile);

        assertTrue(domain.previewPromotion(recipe).eligible());
        domain.promote(recipe);
        domain.writeProjectModel();

        var promoted = new GeneratedProjectModelStore().load(modelFile)
                .findClass("Movies").fields().stream()
                .filter(field -> "locations".equals(field.name()))
                .findFirst().orElseThrow().wikipediaCategoryRule();
        assertEquals("Films set in <value>", promoted.pattern());
        assertEquals(1, curation.sourceRecipes().size(),
                "promotion does not silently erase the curation override");
        assertFalse(domain.previewPromotion(recipe).eligible());
    }

    @Test void promotesAnExactCategoryMappingWithoutRequiringAPattern() throws Exception {
        File modelFile = temp.resolve("deities.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel deity = new GeneratedClassModel("EgyptianDeity");
        deity.addField("iconographicSymbol", datasource.schema.FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        model.rootClass(deity);
        new GeneratedProjectModelStore().save(model, modelFile);
        WikidataDynamicObject instance = new WikidataDynamicObject("Q841", "Horus");
        instance.type("EgyptianDeity");
        SnapshotDomain base = new SnapshotDomain(List.of(instance));
        ManualCuration curation = new ManualCuration(
                temp.resolve("deities.curation.json").toFile());
        var rule = new wikidata.explore.model.WikipediaCategoryRule();
        rule.mappings().add(new wikidata.explore.model.WikipediaCategoryMapping(
                "Falcon deities", "Q43489"));
        FieldSourceRecipe recipe = FieldSourceRecipe.wikipediaCategory(
                "EgyptianDeity", "iconographicSymbol", rule);
        CuratableDomain domain = new CuratableDomain(
                base, curation, base.memberRoots(), List.of(), modelFile);

        assertTrue(domain.previewPromotion(recipe).eligible());
        domain.promote(recipe);
        domain.writeProjectModel();

        var promoted = new GeneratedProjectModelStore().load(modelFile)
                .findClass("EgyptianDeity").fields().getFirst().wikipediaCategoryRule();
        assertEquals(rule.mappings(), promoted.mappings());
        assertFalse(domain.previewPromotion(recipe).eligible());
    }

    @Test void promotesAnObservedInfoboxKeyIntoTheChosenFieldSourceSlot()
            throws Exception {
        CuratableDomain domain = deities(field -> field.mapping().propertyPid("P1080"));
        File modelFile = temp.resolve("deities.model.json").toFile();
        FieldSourceRecipe recipe = FieldSourceRecipe.wikipediaInfobox(
                "EgyptianDeity", "symbol",
                datasource.api.SourceBindingSlot.FALLBACK_FIELD_VALUE,
                "Rendered.Symbol", "Wikipedia infobox value");

        var preview = domain.previewPromotion(recipe);
        assertTrue(preview.eligible(), preview.reason());
        assertEquals("Wikipedia infobox fallback", preview.sourceKind());
        domain.promote(recipe);
        assertNull(new GeneratedProjectModelStore().load(modelFile)
                        .findClass("EgyptianDeity").fields().getFirst().fallbackMapping(),
                "configuration changes the working model; Save remains the writer");
        assertEquals(List.of("Configured EgyptianDeity.symbol from Wikipedia "
                        + "infobox fallback Rendered.Symbol"),
                domain.unsavedModelChanges());

        domain.writeProjectModel();

        var promoted = new GeneratedProjectModelStore().load(modelFile)
                .findClass("EgyptianDeity").fields().getFirst();
        assertEquals(wikidata.explore.model.FieldSourceType.WIKIPEDIA_INFOBOX,
                promoted.fallbackMapping().sourceType());
        assertEquals("Rendered.Symbol", promoted.fallbackMapping().propertyPid());
        assertEquals("P1080", promoted.mapping().propertyPid(),
                "a fallback leaves the primary source as it was");
        FieldSourceRecipe sameSourceWithAnotherLabel = FieldSourceRecipe.wikipediaInfobox(
                "EgyptianDeity", "symbol",
                datasource.api.SourceBindingSlot.FALLBACK_FIELD_VALUE,
                "Rendered.Symbol", "Observed symbol row");
        assertFalse(domain.previewPromotion(sameSourceWithAnotherLabel).eligible(),
                "the same provider, operation, slot and key is one source; its label "
                        + "must not make discovery offer it twice");
    }

    /**
     * Acquisition stores an infobox value's text as it is. In an entity list,
     * "Ra, Thoth" would stand where references belong — a flattened second reading of
     * a row whose links name the actual entities.
     */
    @Test void anInfoboxValueIsNotOfferedForAnEntityOrListField() throws Exception {
        File modelFile = temp.resolve("parents.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel deity = new GeneratedClassModel("EgyptianDeity");
        deity.addField("parents", FieldType.ENTITY,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        deity.addField("epithets", FieldType.STRING,
                wikidata.explore.model.FieldCardinality.COLLECTION);
        model.rootClass(deity);
        new GeneratedProjectModelStore().save(model, modelFile);
        WikidataDynamicObject horus = new WikidataDynamicObject("Q841", "Horus");
        horus.type("EgyptianDeity");
        SnapshotDomain base = new SnapshotDomain(List.of(horus));
        CuratableDomain domain = new CuratableDomain(base,
                new ManualCuration(temp.resolve("parents.curation.json").toFile()),
                base.memberRoots(), List.of(), modelFile);

        for (String field : List.of("parents", "epithets")) {
            var preview = domain.previewPromotion(FieldSourceRecipe.wikipediaInfobox(
                    "EgyptianDeity", field,
                    datasource.api.SourceBindingSlot.FALLBACK_FIELD_VALUE,
                    "Rendered.Parents", "Wikipedia infobox value"));
            assertFalse(preview.eligible(), field);
            assertTrue(preview.reason().contains("single String or Text field"),
                    preview.reason());
        }
    }

    /** The infobox binding says only kind, key and label; a replaced Wikidata primary's
     *  qualifier condition and value language must not be saved into it. */
    @Test void replacingAPrimarySourceLeavesNothingOfThePreviousOneBehind()
            throws Exception {
        CuratableDomain domain = deities(field -> {
            field.mapping().propertyPid("P1080");
            field.mapping().valueLanguage("en");
            field.mapping().qualifierCondition(
                    wikidata.QualifierCondition.of("P518", List.of("Q42")));
        });
        domain.promote(FieldSourceRecipe.wikipediaInfobox("EgyptianDeity", "symbol",
                datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE,
                "Rendered.Symbol", "Wikipedia infobox value"));
        domain.writeProjectModel();

        var saved = new GeneratedProjectModelStore()
                .load(temp.resolve("deities.model.json").toFile())
                .findClass("EgyptianDeity").fields().getFirst();
        assertEquals(wikidata.explore.model.FieldSourceType.WIKIPEDIA_INFOBOX,
                saved.mapping().sourceType());
        assertEquals("Rendered.Symbol", saved.mapping().propertyPid());
        assertNull(saved.mapping().qualifierCondition());
        assertEquals("", saved.mapping().valueLanguage());
        var binding = wikidata.explore.model.FieldSourceBindings.binding(
                saved, datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE);
        assertEquals("", binding.recipe().parameter(
                wikidata.explore.model.FieldSourceBindings.CONDITION_QUALIFIER));
        assertEquals("", binding.recipe().parameter(
                wikidata.explore.model.FieldSourceBindings.VALUE_LANGUAGE));
    }

    private CuratableDomain deities(
            java.util.function.Consumer<wikidata.explore.model.GeneratedFieldModel> symbol)
            throws Exception {
        File modelFile = temp.resolve("deities.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel deity = new GeneratedClassModel("EgyptianDeity");
        symbol.accept(deity.addField("symbol", FieldType.STRING,
                wikidata.explore.model.FieldCardinality.SINGLE));
        model.rootClass(deity);
        new GeneratedProjectModelStore().save(model, modelFile);
        WikidataDynamicObject horus = new WikidataDynamicObject("Q841", "Horus");
        horus.type("EgyptianDeity");
        SnapshotDomain base = new SnapshotDomain(List.of(horus));
        return new CuratableDomain(base,
                new ManualCuration(temp.resolve("deities.curation.json").toFile()),
                base.memberRoots(), List.of(), modelFile);
    }
}
