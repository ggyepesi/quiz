package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReferentClassStampTest {

    private static GeneratedProjectModel model() {
        GeneratedProjectModel p = new GeneratedProjectModel();

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        entityField(nom, "nominee", "Nominee");           // -> bare class
        entityField(nom, "category", "OscarCategories");  // -> VOCABULARY Selection
        entityField(nom, "forWork", "ForWork");           // -> neither: not stamped
        p.addClass(nom);

        // Nominee is a bare referenced-only class (identity-only): it exists, so the
        // ref resolves. ForWork intentionally absent (dangling).
        p.addClass(new GeneratedClassModel("Nominee"));

        // Categories are a closed vocabulary -> a Selection is their type, not a class.
        p.addSelection(new VocabularySelection("OscarCategories"));

        p.rootClass(nom);
        return p;
    }

    private static void entityField(
            GeneratedClassModel c, String name, String target) {
        GeneratedFieldModel f =
                c.addField(name, FieldType.ENTITY, FieldCardinality.SINGLE);
        f.entityClassName(target);
    }

    @Test void stampsModeledRefsAndLeavesDanglingAndStampedAlone() {
        GeneratedProjectModel model = model();

        WikidataDynamicObject nomination =
                new WikidataDynamicObject("Q1", "a nomination");
        nomination.type("Nomination");

        WikidataDynamicObject nominee =
                new WikidataDynamicObject("Q42", "Meryl Streep");
        WikidataDynamicObject category =
                new WikidataDynamicObject("Q102427", "Best Actress");
        WikidataDynamicObject forWork =
                new WikidataDynamicObject("Q7", "The Iron Lady");

        nomination.put("nominee", nominee);
        nomination.put("category", category);
        nomination.put("forWork", forWork);

        int stamped = ReferentClassStamp.apply(model, List.of(nomination));

        assertEquals(2, stamped, "the class ref and the Selection ref are stamped");
        assertEquals("Nominee", nominee.typeName());
        assertEquals("OscarCategories", category.typeName(),
                "a Selection-named target types the referent by the Selection");
        // ForWork is neither a class nor a Selection -> left untyped (a label).
        assertFalse(forWork.hasTypeStamp());
    }

    @Test void stampsEveryElementOfAListReferent() {
        GeneratedProjectModel model = model();

        WikidataDynamicObject nomination =
                new WikidataDynamicObject("Q1", "a shared nomination");
        nomination.type("Nomination");

        WikidataDynamicObject a = new WikidataDynamicObject("Q10", "Co-nominee A");
        WikidataDynamicObject b = new WikidataDynamicObject("Q11", "Co-nominee B");
        nomination.put("nominee", List.of(a, b));

        int stamped = ReferentClassStamp.apply(model, List.of(nomination));

        assertEquals(2, stamped);
        assertEquals("Nominee", a.typeName());
        assertEquals("Nominee", b.typeName());
    }

    @Test void doesNotRestampAnAlreadyTypedReferent() {
        GeneratedProjectModel model = model();

        WikidataDynamicObject nomination =
                new WikidataDynamicObject("Q1", "a nomination");
        nomination.type("Nomination");

        WikidataDynamicObject nominee =
                new WikidataDynamicObject("Q42", "Meryl Streep");
        nominee.type("Person");   // already a member of some served class
        nomination.put("nominee", nominee);

        int stamped = ReferentClassStamp.apply(model, List.of(nomination));

        assertEquals(0, stamped);
        assertEquals("Person", nominee.typeName(), "an existing type is preserved");
    }
}
