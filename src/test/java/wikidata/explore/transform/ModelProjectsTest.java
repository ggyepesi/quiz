package wikidata.explore.transform;

import aux.FlexibleDate;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelProjectsTest {

    private static WikidataDynamicObject typed(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    @Test
    void fillsFromReferencedField() {
        WikidataDynamicObject edition = typed("Q544731", "41st Academy Awards", "Edition");
        edition.put("date", new FlexibleDate(1969));

        WikidataDynamicObject nom = typed("Q1-x", "a nomination", "Nomination");
        nom.put("edition", edition);   // year absent

        new TransformEngine().applyProject(List.of(nom, edition),
                new ProjectConstruct("Nomination", "edition", "date", "year"));

        assertEquals(new FlexibleDate(1969), nom.get("year"));
    }

    @Test
    void neverOverwritesAnExistingValue() {
        WikidataDynamicObject edition = typed("Q544731", "41st Academy Awards", "Edition");
        edition.put("date", new FlexibleDate(1969));

        WikidataDynamicObject nom = typed("Q1-x", "a nomination", "Nomination");
        nom.put("edition", edition);
        nom.put("year", new FlexibleDate(1970));   // already present — must win

        new TransformEngine().applyProject(List.of(nom, edition),
                new ProjectConstruct("Nomination", "edition", "date", "year"));

        assertEquals(new FlexibleDate(1970), nom.get("year"));
    }

    @Test
    void resolvesTheReferentByQidWhenTheDirectReferenceIsBare() {
        // nom.edition points at a BARE edition (no date); the field-bearing one is
        // elsewhere in the pool under the same QID — resolution must find it.
        WikidataDynamicObject bare = typed("Q544731", "41st Academy Awards", "Edition");
        WikidataDynamicObject full = typed("Q544731", "41st Academy Awards", "Edition");
        full.put("date", new FlexibleDate(1969));

        WikidataDynamicObject nom = typed("Q1-x", "a nomination", "Nomination");
        nom.put("edition", bare);

        new TransformEngine().applyProject(List.of(nom, full),
                new ProjectConstruct("Nomination", "edition", "date", "year"));

        assertEquals(new FlexibleDate(1969), nom.get("year"));
    }

    @Test
    void ignoresInstancesOfOtherTypesAndMissingReferents() {
        WikidataDynamicObject other = typed("Q2", "a category", "Category");   // wrong type
        WikidataDynamicObject noEdition = typed("Q3-x", "no edition", "Nomination"); // no via

        new TransformEngine().applyProject(List.of(other, noEdition),
                new ProjectConstruct("Nomination", "edition", "date", "year"));

        assertEquals(null, other.get("year"));
        assertEquals(null, noEdition.get("year"));
    }
}
