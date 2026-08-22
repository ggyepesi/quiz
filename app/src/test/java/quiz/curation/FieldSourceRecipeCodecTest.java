package quiz.curation;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldSourceType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FieldSourceRecipeCodecTest {
    @Test void nativeInfoboxRecipeHasOneMeaningInBothApplications() {
        FieldSourceRecipe scoped = FieldSourceRecipe.wikipediaInfobox(
                "Movie", "locations", "Infobox film.country", "Country");
        var mapping = FieldSourceRecipeCodec.mapping(scoped);
        assertEquals(FieldSourceType.WIKIPEDIA_INFOBOX, mapping.sourceType());
        assertEquals("Infobox film.country", mapping.propertyPid());
        assertEquals(scoped, FieldSourceRecipeCodec.scoped("Movie", "locations", mapping));
    }
}
