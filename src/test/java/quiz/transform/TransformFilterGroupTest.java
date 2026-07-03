package quiz.transform;

import org.junit.jupiter.api.Test;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.QuizableGroup;
import quiz.facet.Facet;
import quiz.facet.FacetGrouper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The view-layer worked example: filter the domain instances (winners only) and
 * group the projected view per category per year — the "Oscar winners per
 * category per year" pipeline on {@link quiz.transform} + {@link FacetGrouper}.
 */
class TransformFilterGroupTest {

    // A generated Nomination-shape: a won flag + category / year / nominee.
    public static class Nomination extends QuizableAdapter {
        public boolean won;
        public String category;
        public int year;
        public String nominee;
        public Nomination() {}
        Nomination(boolean won, String category, int year, String nominee) {
            this.won = won; this.category = category; this.year = year; this.nominee = nominee;
        }
        @Override public String getIdentifier() { return nominee + "/" + category + "/" + year; }
        @Override public String getDisplayName() { return nominee; }
    }

    // The projected view class.
    public static class Winner extends QuizableAdapter {
        public String category;
        public int year;
        public String nominee;
        public Winner() {}
        @Override public String getIdentifier() { return nominee + "/" + category + "/" + year; }
        @Override public String getDisplayName() { return nominee; }
    }

    @Test void winnersFilteredThenGroupedPerCategoryPerYear() {
        List<Nomination> noms = List.of(
                new Nomination(true,  "Best Actor",   1993, "Al Pacino"),
                new Nomination(false, "Best Actor",   1993, "Denzel"),
                new Nomination(true,  "Best Actress", 1993, "Holly Hunter"),
                new Nomination(true,  "Best Actor",   1994, "Tom Hanks"),
                new Nomination(false, "Best Actress", 1993, "Angela"));

        // FILTER to winners, PROJECT to Winner.
        ClassTransformPlan<Nomination, Winner> plan =
                new ClassTransformPlan<>(Nomination.class, Winner.class)
                        .whereFieldEquals("won", true)
                        .copy("category", "category")
                        .copy("year", "year")
                        .copy("nominee", "nominee");

        TransformContext ctx = new TransformRunner().add(plan).run(noms);
        List<Winner> winners = ctx.targets(Winner.class);
        assertEquals(3, winners.size(), "only the 3 won=true nominations survive the filter");

        // GROUP per category, then per year (nested drill-down).
        QuizableGroup root = FacetGrouper.groupNested("Winners", winners,
                List.of(Facet.field("category"), Facet.field("year")));

        assertEquals(3, root.getMembers().size(), "all winners at the root");

        QuizableGroup bestActor = root.getChild("category").getChild("Best Actor");
        assertEquals(2, bestActor.getMembers().size(), "Pacino '93 + Hanks '94");
        assertEquals("Al Pacino", bestActor.getChild("year").getChild("1993")
                .getMembers().iterator().next().getDisplayName());
        assertEquals("Tom Hanks", bestActor.getChild("year").getChild("1994")
                .getMembers().iterator().next().getDisplayName());

        QuizableGroup bestActress = root.getChild("category").getChild("Best Actress");
        assertEquals(1, bestActress.getMembers().size(), "only the winner, not the loser");

        // Denzel / Angela (won=false) never became Winners, so no bucket for them.
        assertNull(bestActor.getChild("year").getChild("1993").getChild("Denzel"));
    }

    @Test void viewWithKeepingPreservesSourcesAndGroups() {
        List<Nomination> noms = List.of(
                new Nomination(true,  "Best Actor", 1993, "Al Pacino"),
                new Nomination(false, "Best Actor", 1993, "Denzel"),
                new Nomination(true,  "Best Actor", 1994, "Tom Hanks"));

        // A View = filter-only plan (keep the sources) + nested grouping.
        View view = new View("Winners", Nomination.class)
                .plan(ClassTransformPlan.keeping(Nomination.class)
                        .whereFieldEquals("won", true))
                .groupBy(Facet.field("category"), Facet.field("year"));

        // Members are the SAME source instances (identity) — display name intact.
        List<? extends Quizable> members = view.members(noms);
        assertEquals(2, members.size());
        assertTrue(members.contains(noms.get(0)) && members.contains(noms.get(2)),
                "kept the winner source instances, not projections");

        QuizableGroup root = view.render(noms);
        assertEquals("Al Pacino", root.getChild("category").getChild("Best Actor")
                .getChild("year").getChild("1993").getMembers().iterator().next()
                .getDisplayName());
    }
}
