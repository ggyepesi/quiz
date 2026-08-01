package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import objectview.Viewable;
import quiz.transform.DynamicViewable;
import quiz.transform.app.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROJECT materializes a new class from selected (possibly nested) fields, and the
 * WorkingDomain feeds it back into the pool so a later operation can consume it.
 */
class ProjectorTest {

    @Test void projectMaterializesAndFeedsBackTheDerivedClass() {
        // Stamped: a real modeled class. (An UNSTAMPED bare reference would be
        // collapsed to its display-name string by SnapshotDomain — by design.)
        WikidataDynamicObject cat = new WikidataDynamicObject("Q1", "Best Picture");
        cat.type("Category");
        WikidataDynamicObject n1 = nomination("N1", cat, 2000);
        WikidataDynamicObject n2 = nomination("N2", cat, 2001);

        WorkingDomain domain = new WorkingDomain(
                new SnapshotDomain(List.of(cat, n1, n2)));

        // PROJECT Nomination -> Nom(category, year).
        DerivedClass nom = Projector.project(domain, "Nomination", List.of(
                new DomainField("Nomination", "category", true, false),
                new DomainField("Nomination", "year", false, false)), "Nom");
        domain.add(nom);

        // Materialized: one per nomination, stamped Nom, carrying the fields.
        assertEquals(2, nom.instances().size());
        Viewable first = nom.instances().get(0);
        assertEquals("Nom", first.typeName());
        assertEquals(2000, ((DynamicViewable) first).get("year"));

        // Fed back: the new type + its fields (with reference target) are in the domain.
        assertTrue(domain.types().contains("Nom"));
        assertTrue(domain.fields("Nom").stream().anyMatch(f -> f.field().equals("category")));
        assertEquals("Category",
                domain.fieldSchema("Nom").field("category").targetType(),
                "projection must retain the source reference target");
    }

    private static WikidataDynamicObject nomination(String id, WikidataDynamicObject cat, int year) {
        WikidataDynamicObject n = new WikidataDynamicObject(id, id);
        n.type("Nomination");
        n.put("category", cat);
        n.put("year", year);
        return n;
    }
}
