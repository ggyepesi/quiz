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
                new OperationSpec(OperationKind.GROUP_BY,
                        new DomainField("Nomination", "category", true, false), null),
                new OperationSpec(OperationKind.GROUP_BY,
                        new DomainField("Nomination", "year", false, false), null));

        View view = ViewCompiler.compile("winners", "Nomination", ops,
                WikidataDynamicObject.class);

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

    @Test void independentGroupIsAParallelDimensionNotNested() {
        WikidataDynamicObject bestPicture = new WikidataDynamicObject("Q1", "Best Picture");
        List<WikidataDynamicObject> pool = List.of(
                bestPicture,
                nomination("N1", true, bestPicture, 2000),
                nomination("N3", true, bestPicture, 2001));

        OperationSpec cat = new OperationSpec(OperationKind.GROUP_BY,
                new DomainField("Nomination", "category", true, false), null);
        OperationSpec year = new OperationSpec(OperationKind.GROUP_BY,
                new DomainField("Nomination", "year", false, false), null);
        year.independent = true;   // parallel dimension off the root

        QuizableGroup root = ViewCompiler.compile("v", "Nomination", List.of(cat, year),
                WikidataDynamicObject.class).render(pool);

        // Two FACET dimensions off the root (category | year), not one nested chain.
        assertEquals(2, root.getChildren().size());
        for (QuizableGroup facet : root.getChildren()) {
            for (QuizableGroup bucket : facet.getChildren()) {
                assertTrue(bucket.getChildren().isEmpty(), "independent bucket is a leaf");
            }
        }
    }

    @Test void nestedGroupDrillsIntoTheParentBucket() {
        WikidataDynamicObject bestPicture = new WikidataDynamicObject("Q1", "Best Picture");
        List<WikidataDynamicObject> pool = List.of(
                bestPicture, nomination("N1", true, bestPicture, 2000));

        OperationSpec cat = new OperationSpec(OperationKind.GROUP_BY,
                new DomainField("Nomination", "category", true, false), null);
        OperationSpec year = new OperationSpec(OperationKind.GROUP_BY,
                new DomainField("Nomination", "year", false, false), null);
        // year nested (default): one chain, category → year.

        QuizableGroup root = ViewCompiler.compile("v", "Nomination", List.of(cat, year),
                WikidataDynamicObject.class).render(pool);

        assertEquals(1, root.getChildren().size(), "one nested dimension chain");
        for (QuizableGroup facet : root.getChildren()) {          // "by category"
            for (QuizableGroup bucket : facet.getChildren()) {     // Best Picture
                assertFalse(bucket.getChildren().isEmpty(), "nested bucket drills further");
            }
        }
    }

    private static void collectLabels(QuizableGroup g, java.util.Set<String> out) {
        for (QuizableGroup c : g.getChildren()) {
            out.add(c.getDisplayName());
            collectLabels(c, out);
        }
    }

    @Test void groupsByNestedPathFanningOutThroughACollection() {
        // A State with two languages; group by the NESTED path languages.iso —
        // the member lands under each language's iso code.
        WikidataDynamicObject en = new WikidataDynamicObject("Q2", "English");
        en.put("iso", "en");
        WikidataDynamicObject fr = new WikidataDynamicObject("Q3", "French");
        fr.put("iso", "fr");
        WikidataDynamicObject state = new WikidataDynamicObject("Q1", "Wonderland");
        state.type("State");
        state.merge("languages", en);
        state.merge("languages", fr);

        View view = ViewCompiler.compile("langs", "State", List.of(
                new OperationSpec(OperationKind.GROUP_BY,
                        new DomainField("State", "languages.iso", false, false), null)),
                WikidataDynamicObject.class);

        QuizableGroup root = view.render(List.of(state, en, fr));
        java.util.Set<String> labels = new java.util.HashSet<>();
        collectLabels(root, labels);
        assertTrue(labels.contains("en") && labels.contains("fr"), labels.toString());
    }

    @Test void memberTypeFilterExcludesOtherClasses() {
        WikidataDynamicObject cat = new WikidataDynamicObject("Q1", "Best Picture");
        List<WikidataDynamicObject> pool = List.of(
                cat, nomination("N1", true, cat, 2000));

        View view = ViewCompiler.compile("v", "Nomination", List.of(),
                WikidataDynamicObject.class);
        // Only the Nomination is a member; the category entity is not.
        assertEquals(1, view.members(pool).size());
        assertTrue(view.members(pool).get(0).getIdentifier().equals("N1"));
    }
}
