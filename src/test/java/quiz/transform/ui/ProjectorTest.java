package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.transform.DynamicQuizable;
import quiz.transform.View;
import quiz.transform.app.SnapshotDomain;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROJECT materializes a new class from selected (possibly nested) fields, and the
 * WorkingDomain feeds it back into the pool so a later operation can group it.
 */
class ProjectorTest {

    @Test void projectThenGroupTheDerivedClass() {
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
        Quizable first = nom.instances().get(0);
        assertEquals("Nom", first.typeName());
        assertEquals(2000, ((DynamicQuizable) first).get("year"));

        // Fed back: the new type + its fields are in the working domain.
        assertTrue(domain.types().contains("Nom"));
        assertTrue(domain.fields("Nom").stream().anyMatch(f -> f.field().equals("category")));

        // A later operation groups the DERIVED class by a projected field.
        View view = ViewCompiler.compile("by cat", "Nom", List.of(
                new OperationSpec(OperationKind.GROUP_BY,
                        new DomainField("Nom", "category", true, false), null)),
                domain.universe());
        QuizableGroup root = view.render(domain.instances());
        java.util.Set<String> labels = new java.util.HashSet<>();
        collect(root, labels);
        assertTrue(labels.contains("Best Picture"), labels.toString());
    }

    private static WikidataDynamicObject nomination(String id, WikidataDynamicObject cat, int year) {
        WikidataDynamicObject n = new WikidataDynamicObject(id, id);
        n.type("Nomination");
        n.put("category", cat);
        n.put("year", year);
        return n;
    }

    private static void collect(QuizableGroup g, java.util.Set<String> out) {
        for (QuizableGroup c : g.getChildren()) {
            out.add(c.getDisplayName());
            collect(c, out);
        }
    }
}
