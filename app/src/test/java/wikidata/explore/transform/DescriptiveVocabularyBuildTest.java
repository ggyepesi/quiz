package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DescriptiveVocabularyBuildTest {

    /** A model where Nominee (referenced-only) has a `type` field targeting the
     *  NomineeType vocabulary. */
    private static GeneratedProjectModel modelWithNomineeType() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.addClass(nom);
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        nominee.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("NomineeType");   // target = a vocabulary
        model.addClass(nominee);
        model.rootClass(nom);
        return model;
    }

    private static WikidataDynamicObject nominee(String qid, String name, String... typeQids) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type("Nominee");
        List<WikidataDynamicObject> types = new java.util.ArrayList<>();
        for (String t : typeQids) {
            types.add(new WikidataDynamicObject(t, t));
        }
        o.put("type", types);
        return o;
    }

    /** The vocabulary is exactly the distinct types on the SERVED pool — a type whose
     *  only bearer is absent from the pool (pruned) does NOT appear. */
    @Test void derivesTheVocabularyFromTheServedPoolOnly() {
        GeneratedProjectModel model = modelWithNomineeType();

        WikidataDynamicObject a = nominee("Q42", "Meryl Streep", "Q5");        // human
        WikidataDynamicObject b = nominee("Q7", "The Iron Lady", "Q11424");    // film
        // A country nominee that WOULD contribute Q3624078 (sovereign state) — but it
        // was pruned, so it is not in the served pool handed to the build.
        // (intentionally NOT added to the pool)

        DescriptiveVocabularyBuild.apply(model, List.of(a, b), null);

        VocabularySelection vocab =
                (VocabularySelection) model.findSelection("NomineeType");
        assertEquals(List.of("Q5", "Q11424"), vocab.valueQids(),
                "only the types actually present on served nominees");
    }

    /** An authored constraint vocabulary (target of a NON-referenced class's field)
     *  is out of scope and left untouched. */
    @Test void leavesAnAuthoredConstraintVocabularyUntouched() {
        GeneratedProjectModel model = modelWithNomineeType();
        VocabularySelection authored = new VocabularySelection("OscarCategories");
        authored.valueQids(new java.util.ArrayList<>(List.of("Q1", "Q2", "Q3")));
        model.addSelection(authored);

        // The reify class Nomination.category targets it — but Nomination is not a
        // REFERENCED class, so it is not a descriptive-vocab feed.
        model.findClass("Nomination")
                .addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("OscarCategories");

        WikidataDynamicObject a = nominee("Q42", "Meryl Streep", "Q5");
        DescriptiveVocabularyBuild.apply(model, List.of(a), null);

        assertEquals(List.of("Q1", "Q2", "Q3"),
                ((VocabularySelection) model.findSelection("OscarCategories")).valueQids(),
                "authored constraint vocab is never re-derived from the served subset");
        assertEquals(List.of("Q5"),
                ((VocabularySelection) model.findSelection("NomineeType")).valueQids());
    }
}
