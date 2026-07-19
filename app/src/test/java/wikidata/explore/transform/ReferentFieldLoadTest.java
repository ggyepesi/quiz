package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReferentFieldLoadTest {

    /** ForWork is referenced-only (Nomination.forWork targets it) with a declared
     *  genre (P136) field; a ForWork referent gets its genre loaded from P136. */
    @Test void loadsDeclaredPropertyFieldOntoReferencedClassReferents() {
        GeneratedProjectModel model = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.addClass(nom);

        GeneratedClassModel forWork = new GeneratedClassModel("ForWork");
        GeneratedFieldModel genre =
                forWork.addField("genre", FieldType.ENTITY, FieldCardinality.COLLECTION);
        genre.mapping().propertyPid("P136");
        model.addClass(forWork);
        model.rootClass(nom);

        WikidataDynamicObject work = new WikidataDynamicObject("Q7", "The Iron Lady");
        work.type("ForWork");   // already class-stamped, as after ReferentClassStamp

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q7", "The Iron Lady", Map.of("P136", List.of("Q130232")))
                .entity("Q130232", "drama film");

        int loaded = ReferentFieldLoad.apply(model, List.of(work), api, null);

        assertEquals(1, loaded);
        Object genreVal = work.get("genre");
        assertInstanceOf(List.class, genreVal);   // COLLECTION -> a list
        assertEquals("drama film",
                ((WikidataDynamicObject) ((List<?>) genreVal).get(0)).getDisplayName());
    }

    /** No declared property-fields on the referenced class -> nothing loads. */
    @Test void doesNothingWhenTheReferencedClassHasNoPropertyFields() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");
        model.addClass(nom);
        model.addClass(new GeneratedClassModel("ForWork"));   // bare, no fields
        model.rootClass(nom);

        WikidataDynamicObject work = new WikidataDynamicObject("Q7", "The Iron Lady");
        work.type("ForWork");
        FakeWikidataApiClient api = new FakeWikidataApiClient().entity("Q7", "The Iron Lady");

        assertEquals(0, ReferentFieldLoad.apply(model, List.of(work), api, null));
        assertNull(work.get("genre"));
    }
}
