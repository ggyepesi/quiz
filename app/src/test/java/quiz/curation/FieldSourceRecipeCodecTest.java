package quiz.curation;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldSourceType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FieldSourceRecipeCodecTest {

    @Test void anAdditionalSourceHasOneMeaningInBothApplications() {
        FieldSourceRecipe scoped = FieldSourceRecipe.additionalSource("Movie", "locations",
                FieldSourceType.WIKIPEDIA_INFOBOX.name(), "Infobox film.country", "Country");

        var mapping = FieldSourceRecipeCodec.mapping(scoped);

        assertEquals(FieldSourceType.WIKIPEDIA_INFOBOX, mapping.sourceType());
        assertEquals("Infobox film.country", mapping.propertyPid());
        assertEquals(scoped, FieldSourceRecipeCodec.scoped("Movie", "locations", mapping));
    }

    /** #108: a DBpedia choice was made the same way and then not recorded, so it lasted
     *  until the panel closed. Nothing about it is special enough to need its own code. */
    @Test void aDbpediaChoiceIsRecordedTheSameWayAnInfoboxChoiceIs() {
        FieldSourceRecipe recorded = FieldSourceRecipeCodec.scoped("Movie", "locations",
                mapping(FieldSourceType.DBPEDIA, "country", "DBpedia infobox property"));

        assertNotNull(recorded, "a chosen source that is not recorded is not a choice");
        assertEquals(FieldSourceRecipe.ADDITIONAL_SOURCE, recorded.provider());
        assertEquals(FieldSourceType.DBPEDIA,
                FieldSourceRecipeCodec.mapping(recorded).sourceType());
        assertEquals("country", FieldSourceRecipeCodec.mapping(recorded).propertyPid());
    }

    /** The choices are alternatives, so they share one slot: recording either must leave
     *  the field with exactly one additional source, not two competing records. */
    @Test void choosingOneAdditionalSourceReplacesTheOther() {
        ManualCuration curation = new ManualCuration(null);
        curation.putSourceRecipe(FieldSourceRecipeCodec.scoped("Movie", "locations",
                mapping(FieldSourceType.WIKIPEDIA_INFOBOX, "Infobox film.country", "")));

        curation.putSourceRecipe(FieldSourceRecipeCodec.scoped("Movie", "locations",
                mapping(FieldSourceType.DBPEDIA, "country", "")));

        assertEquals(1, curation.sourceRecipes().size());
        assertEquals(FieldSourceType.DBPEDIA, FieldSourceRecipeCodec.mapping(
                curation.sourceRecipe("Movie", "locations",
                        FieldSourceRecipe.ADDITIONAL_SOURCE)).sourceType());
    }

    @Test void aSourceThatIsNotReadAfterExtractionIsNotADatasetOverride() {
        assertNull(FieldSourceRecipeCodec.scoped("Movie", "locations",
                mapping(FieldSourceType.SPARQL, "P840", "narrative location")),
                "a Wikidata property is the model's business, not the sidecar's");
    }

    @Test void aRecipeNamingAnUnreadableKindIsIgnoredRatherThanFatal() {
        assertNull(FieldSourceRecipeCodec.mapping(FieldSourceRecipe.additionalSource(
                "Movie", "locations", "SOME_LATER_SOURCE", "whatever", "")));
    }

    @Test void aCategoryRuleIsNotAnAdditionalSource() {
        assertNull(FieldSourceRecipeCodec.mapping(FieldSourceRecipe.wikipediaCategory(
                "Movie", "locations", "Films set in <value>", null)));
    }

    /** The whole point of #108: the choice must still be there the next time the dataset
     *  is opened. Recording it in memory only meant it lasted until the panel closed. */
    @org.junit.jupiter.api.Test void aRecordedChoiceSurvivesTheSidecarRoundTrip(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.io.File file = dir.resolve("films.curation.json").toFile();
        ManualCuration saved = new ManualCuration(file);
        saved.putSourceRecipe(FieldSourceRecipeCodec.scoped("Movie", "locations",
                mapping(FieldSourceType.DBPEDIA, "country", "DBpedia infobox property")));
        saved.save();

        ManualCuration reloaded = new ManualCuration(file).load();
        var mapping = FieldSourceRecipeCodec.mapping(reloaded.sourceRecipe(
                "Movie", "locations", FieldSourceRecipe.ADDITIONAL_SOURCE));

        assertNotNull(mapping, "a choice that does not survive reload was not recorded");
        assertEquals(FieldSourceType.DBPEDIA, mapping.sourceType());
        assertEquals("country", mapping.propertyPid());
        assertEquals("DBpedia infobox property", mapping.propertyLabel());
    }

    private static FieldSourceMapping mapping(FieldSourceType type, String property,
            String label) {
        FieldSourceMapping mapping = new FieldSourceMapping();
        mapping.sourceType(type);
        mapping.propertyPid(property);
        mapping.propertyLabel(label);
        return mapping;
    }
}
