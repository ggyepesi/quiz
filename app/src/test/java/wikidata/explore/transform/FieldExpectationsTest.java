package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldExpectation;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldExpectationsTest {

    private static WikidataDynamicObject ent(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    private static GeneratedProjectModel modelWithEdition(FieldExpectation level) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new wikidata.explore.model.StatementClassSource(
                "OscarNominations", "P1411"));   // reifying class
        GeneratedFieldModel edition =
                nom.addField("edition", FieldType.ENTITY, FieldCardinality.SINGLE);
        edition.expectation(level);
        // A statement class states its key; nothing chooses one for it. This is what
        // the editor offers — the triple's own components — accepted explicitly.
        nom.canonical().keyFields().addAll(
                wikidata.explore.model.StatementIdentity.structuralKey(nom));
        project.addClass(nom);
        return project;
    }

    private static List<WikidataDynamicObject> pool() {
        WikidataDynamicObject good = ent("N1", "has ceremony", "Nomination");
        good.put("edition", ent("Q66707607", "95th Academy Awards", "Edition"));
        WikidataDynamicObject missing = ent("N2", "no ceremony", "Nomination");
        return new ArrayList<>(List.of(good, missing));
    }

    @Test void requiredDropsTheMissing() {
        GeneratedProjectModel project = modelWithEdition(FieldExpectation.REQUIRED);
        List<WikidataDynamicObject> pool = pool();

        FieldExpectations.Result r = FieldExpectations.apply(project, pool, null);

        assertEquals(1, r.dropped().size());
        assertEquals(1, pool.size(), "the ceremony-less record is dropped");
        assertEquals(2, r.coverage().get(0).total());
        assertEquals(1, r.coverage().get(0).present());
    }

    @Test void expectedKeepsTheMissingButReportsCoverage() {
        GeneratedProjectModel project = modelWithEdition(FieldExpectation.EXPECTED);
        List<WikidataDynamicObject> pool = pool();

        FieldExpectations.Result r = FieldExpectations.apply(project, pool, null);

        assertTrue(r.dropped().isEmpty(), "EXPECTED never drops");
        assertEquals(2, pool.size(), "both records kept");
        FieldExpectations.FieldCoverage cov = r.coverage().get(0);
        assertEquals("edition", cov.fieldName());
        assertEquals(2, cov.total());
        assertEquals(1, cov.present());
        assertEquals(1, cov.missing());
    }

    @Test void noneIsInert() {
        GeneratedProjectModel project = modelWithEdition(FieldExpectation.NONE);
        List<WikidataDynamicObject> pool = pool();

        FieldExpectations.Result r = FieldExpectations.apply(project, pool, null);

        assertTrue(r.dropped().isEmpty());
        assertTrue(r.coverage().isEmpty(), "NONE fields aren't checked or reported");
        assertEquals(2, pool.size());
    }

    @Test void nonReifiedClassesAreUntouched() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");   // not reifying
        src.addField("target", FieldType.ENTITY, FieldCardinality.SINGLE)
                .expectation(FieldExpectation.REQUIRED);
        project.addClass(src);
        List<WikidataDynamicObject> pool =
                new ArrayList<>(List.of(ent("Q1", "no target", "OscarNominations")));

        FieldExpectations.Result r = FieldExpectations.apply(project, pool, null);

        assertTrue(r.dropped().isEmpty());
        assertFalse(pool.isEmpty());
    }
}
