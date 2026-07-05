package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import quiz.QuizableGroup;
import quiz.transform.View;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transform pipeline compiles to a real View: a FILTER keeps only matching
 * members, and GROUP operations (reference = invert, value) bucket them.
 */
class ViewCompilerTest {

    private static WikidataDynamicObject nomination(
            String qid, boolean won, WikidataDynamicObject category, int year) {
        WikidataDynamicObject n = new WikidataDynamicObject(qid, qid);
        n.type("Nomination");
        n.put("won", won);
        n.put("category", category);
        n.put("year", year);
        return n;
    }

    @Test void filterKeepsWinnersAndGroupsThem() {
        WikidataDynamicObject bestPicture =
                new WikidataDynamicObject("Q1", "Best Picture");
        List<WikidataDynamicObject> pool = List.of(
                bestPicture,
                nomination("N1", true, bestPicture, 2000),
                nomination("N2", false, bestPicture, 2000),
                nomination("N3", true, bestPicture, 2001));

        List<OperationSpec> ops = List.of(
                new OperationSpec(OperationKind.FILTER,
                        new DomainField("Nomination", "won", false, false), Boolean.TRUE),
                new OperationSpec(OperationKind.GROUP_BY_REFERENCE,
                        new DomainField("Nomination", "category", true, false), null),
                new OperationSpec(OperationKind.GROUP_BY_VALUE,
                        new DomainField("Nomination", "year", false, false), null));

        View view = ViewCompiler.compile("winners", "Nomination", ops);

        // FILTER won == true keeps N1 and N3 (not N2, not the category entity).
        assertEquals(2, view.members(pool).size());

        // Grouped: the tree contains a "Best Picture" bucket and year buckets.
        QuizableGroup root = view.render(pool);
        assertFalse(root.getChildren().isEmpty());
        java.util.Set<String> labels = new java.util.HashSet<>();
        collectLabels(root, labels);
        assertTrue(labels.contains("Best Picture"), labels.toString());
        assertTrue(labels.contains("2000") && labels.contains("2001"), labels.toString());
    }

    private static void collectLabels(QuizableGroup g, java.util.Set<String> out) {
        for (QuizableGroup c : g.getChildren()) {
            out.add(c.getDisplayName());
            collectLabels(c, out);
        }
    }

    @Test void memberTypeFilterExcludesOtherClasses() {
        WikidataDynamicObject cat = new WikidataDynamicObject("Q1", "Best Picture");
        List<WikidataDynamicObject> pool = List.of(
                cat, nomination("N1", true, cat, 2000));

        View view = ViewCompiler.compile("v", "Nomination", List.of());
        // Only the Nomination is a member; the category entity is not.
        assertEquals(1, view.members(pool).size());
        assertTrue(view.members(pool).get(0).getIdentifier().equals("N1"));
    }
}
