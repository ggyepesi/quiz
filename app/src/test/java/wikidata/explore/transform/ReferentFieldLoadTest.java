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

    /** A referent that exists ONLY nested inside another record (never a top-level
     *  pool entry) is still found and loaded — e.g. a Ceremony as a Nomination's P805
     *  qualifier value. The pool passed in contains only the Nomination. */
    @Test void loadsOntoAReferentReachableOnlyThroughANestedField() {
        GeneratedProjectModel model = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("ceremony", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Ceremony");
        model.addClass(nom);

        GeneratedClassModel ceremonyClass = new GeneratedClassModel("Ceremony");
        ceremonyClass.addField("year", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().propertyPid("P585");
        model.addClass(ceremonyClass);
        model.rootClass(nom);

        // The ceremony is stamped and nested inside the nomination, but NOT a member
        // of the pool the caller passes (mirrors a qualifier-only referent).
        WikidataDynamicObject ceremony =
                new WikidataDynamicObject("Q100", "97th Academy Awards");
        ceremony.type("Ceremony");
        WikidataDynamicObject nomination =
                new WikidataDynamicObject("Q900$stmt", "The Brutalist — Best Picture");
        nomination.type("Nomination");
        nomination.put("ceremony", ceremony);

        FakeWikidataApiClient api = new FakeWikidataApiClient()
                .statement("Q100", "P585", "Q100$s1", "+2025-03-02T00:00:00Z", Map.of());

        int loaded = ReferentFieldLoad.apply(
                model, List.of(nomination), api, null);   // pool = Nomination only

        assertEquals(1, loaded);
        assertInstanceOf(aux.FlexibleDate.class, ceremony.get("year"));
        assertEquals(2025, ((aux.FlexibleDate) ceremony.get("year")).getYear());
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
