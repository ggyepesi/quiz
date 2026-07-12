package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiredFieldRestrictionsTest {

    private static WikidataDynamicObject ent(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    // A reified (statement) class whose `edition` field is marked required.
    private static GeneratedProjectModel modelWithRequiredEdition() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSourceClass("OscarNominations");   // makes it a reifying class
        nom.addField("edition", FieldType.ENTITY, FieldCardinality.SINGLE)
                .required(true);
        project.addClass(nom);
        return project;
    }

    @Test void dropsReifiedRecordsMissingARequiredField() {
        GeneratedProjectModel project = modelWithRequiredEdition();

        WikidataDynamicObject good = ent("N1", "has ceremony", "Nomination");
        good.put("edition", ent("Q66707607", "95th Academy Awards", "Edition"));
        WikidataDynamicObject phantom = ent("N2", "no ceremony", "Nomination");
        // no edition — the ceremony-less phantom (an absent P805 after subject-default off)

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(good, phantom));
        List<WikidataDynamicObject> dropped =
                RequiredFieldRestrictions.apply(project, pool, null);

        assertEquals(List.of(phantom), dropped);
        assertTrue(pool.contains(good), "record with the required field is kept");
        assertFalse(pool.contains(phantom), "ceremony-less record is removed from the pool");
    }

    @Test void nonReifiedClassesAreUntouched() {
        // required on a SPARQL class is enforced at query time, not here.
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");   // no source class
        src.addField("target", FieldType.ENTITY, FieldCardinality.SINGLE).required(true);
        project.addClass(src);

        WikidataDynamicObject bare = ent("Q1", "no target", "OscarNominations");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(bare));

        assertTrue(RequiredFieldRestrictions.apply(project, pool, null).isEmpty());
        assertTrue(pool.contains(bare), "non-reified records are not dropped here");
    }
}
