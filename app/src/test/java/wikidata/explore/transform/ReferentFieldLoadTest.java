package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;

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

    /** A DATE property-field on a referenced class loads as a FlexibleDate from the
     *  statement's literal value — e.g. a Ceremony's year/date (P585). */
    @Test void loadsADateLiteralFieldOntoReferents() {
        GeneratedProjectModel model = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Ceremony");
        model.addClass(nom);

        GeneratedClassModel ceremonyClass = new GeneratedClassModel("Ceremony");
        GeneratedFieldModel year =
                ceremonyClass.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().propertyPid("P585");
        model.addClass(ceremonyClass);
        model.rootClass(nom);

        WikidataDynamicObject ceremony =
                new WikidataDynamicObject("Q100", "97th Academy Awards");
        ceremony.type("Ceremony");

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .statement("Q100", "P585", "Q100$s1", "+2024-03-10T00:00:00Z", Map.of());

        int loaded = ReferentFieldLoad.apply(model, List.of(ceremony), api, null);

        assertEquals(1, loaded);
        Object y = ceremony.get("year");
        assertInstanceOf(aux.FlexibleDate.class, y);
        assertEquals(2024, ((aux.FlexibleDate) y).getYear());
    }

    /** Loading a field whose target names a (not-yet-existing) vocabulary auto-creates
     *  and fills it from the distinct loaded values — the descriptive vocab, for free. */
    @Test void buildsAVocabularyFromTheLoadedFieldValues() {
        GeneratedProjectModel model = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.addClass(nom);

        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        GeneratedFieldModel type =
                nominee.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        type.mapping().propertyPid("P31");
        type.entityClassName("NomineeTypes");   // target = a descriptive vocabulary
        model.addClass(nominee);

        // NomineeTypes intentionally NOT pre-created — the load auto-creates it.
        model.rootClass(nom);

        WikidataDynamicObject a = new WikidataDynamicObject("Q42", "Meryl Streep");
        a.type("Nominee");
        WikidataDynamicObject b = new WikidataDynamicObject("Q7", "The Iron Lady");
        b.type("Nominee");

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .entity("Q42", "Meryl Streep", Map.of("P31", List.of("Q5")))       // human
                .entity("Q7", "The Iron Lady", Map.of("P31", List.of("Q11424")))   // film
                .entity("Q5", "human")
                .entity("Q11424", "film");

        ReferentFieldLoad.apply(model, List.of(a, b), api, null);

        VocabularySelection built =
                (VocabularySelection) model.findSelection("NomineeTypes");
        assertEquals(List.of("Q5", "Q11424"), built.valueQids(),
                "the vocabulary is the distinct P31 values actually loaded");
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
