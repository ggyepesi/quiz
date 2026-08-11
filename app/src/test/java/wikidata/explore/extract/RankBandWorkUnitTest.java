package wikidata.explore.extract;

import batch.WorkUnit;
import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bands partition a membership scan the endpoint will not complete in one request.
 *
 * <p>Two properties carry the whole design: the bands must be DISJOINT (or members are
 * counted twice) and must COVER the measure exactly (or members vanish from the run with
 * nothing to show it). Both are easy to break while editing widths, so both are pinned.
 */
class RankBandWorkUnitTest {

    private static RuleNode films() {
        RuleNode node = new RuleNode("Movies", "movies");
        node.sourceQid("Q11424");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(20000);
        node.rankBySitelinks(true);
        return node;
    }

    private static WikidataSparqlClient recording(List<String> issued) {
        return new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String sparql) {
                issued.add(sparql);
                return List.of();
            }
        };
    }

    /** Walks every leaf of a unit's split tree to the given depth, in order. */
    private static List<long[]> leaves(RankBandWorkUnit unit, int depth) {
        if (depth == 0 || unit.split().isEmpty()) {
            return List.of(new long[] { unit.from(), unit.until() });
        }
        List<long[]> out = new ArrayList<>();
        for (WorkUnit<List<WikidataBinding>> part : unit.split()) {
            out.addAll(leaves((RankBandWorkUnit) part, depth - 1));
        }
        return out;
    }

    @Test void descendingBandsCoverTheMeasureWithoutGapsOrOverlaps() throws Exception {
        try (WikidataSparqlClient client = recording(new ArrayList<>())) {
            List<RankBandWorkUnit> bands =
                    RankBandWorkUnit.descendingBands(films(), client, 64);

            assertEquals(RuleNode.UNBOUNDED, bands.get(0).until(),
                         "the first band is open at the top — nothing above it is missed");
            assertEquals(64, bands.get(0).from());
            assertEquals(0, bands.get(bands.size() - 1).from(),
                         "the last band reaches zero — nothing below it is missed");

            // Each band starts exactly where the previous one ended: disjoint and gapless.
            for (int i = 1; i < bands.size(); i++) {
                assertEquals(bands.get(i - 1).from(), bands.get(i).until(),
                             "gap or overlap between band " + (i - 1) + " and " + i);
            }
        }
    }

    @Test void bandsAreOrderedMostNotableFirstSoTheScanCanStopEarly() throws Exception {
        try (WikidataSparqlClient client = recording(new ArrayList<>())) {
            List<RankBandWorkUnit> bands =
                    RankBandWorkUnit.descendingBands(films(), client, 64);

            for (int i = 1; i < bands.size(); i++) {
                assertTrue(bands.get(i).from() < bands.get(i - 1).from(),
                           "bands must descend, or an early stop keeps the wrong members");
            }
        }
    }

    @Test void splittingAFiniteBandHalvesItAndStillCoversIt() throws Exception {
        try (WikidataSparqlClient client = recording(new ArrayList<>())) {
            List<long[]> parts = leaves(
                    new RankBandWorkUnit(films(), 20, 40, client), 1);

            assertEquals(2, parts.size());
            assertEquals(40, parts.get(0)[1], "the upper half keeps the original ceiling");
            assertEquals(parts.get(0)[0], parts.get(1)[1], "the halves meet exactly");
            assertEquals(20, parts.get(1)[0], "the lower half keeps the original floor");
        }
    }

    @Test void splittingTheOpenTopBandRaisesItInsteadOfHalving() throws Exception {
        try (WikidataSparqlClient client = recording(new ArrayList<>())) {
            List<? extends WorkUnit<List<WikidataBinding>>> parts =
                    new RankBandWorkUnit(films(), 64, RuleNode.UNBOUNDED, client).split();

            RankBandWorkUnit upper = (RankBandWorkUnit) parts.get(0);
            RankBandWorkUnit lower = (RankBandWorkUnit) parts.get(1);
            assertEquals(RuleNode.UNBOUNDED, upper.until(), "the top stays open");
            assertEquals(128, upper.from());
            assertEquals(64, lower.from());
            assertEquals(128, lower.until());
            // [64,128) + [128,∞) is exactly the original [64,∞).
        }
    }

    @Test void aSingleMeasureValueCannotBeNarrowedFurther() throws Exception {
        try (WikidataSparqlClient client = recording(new ArrayList<>())) {
            assertTrue(new RankBandWorkUnit(films(), 7, 8, client).split().isEmpty(),
                       "the executor then fails the run rather than looping");
        }
    }

    @Test void theBandReachesTheQueryAsAFilterOnTheRankMeasure() throws Exception {
        List<String> issued = new ArrayList<>();
        try (WikidataSparqlClient client = recording(issued)) {
            RankBandWorkUnit unit = new RankBandWorkUnit(films(), 20, 40, client);
            String request = unit.request();

            unit.execute();

            String query = issued.get(0);
            assertTrue(query.contains("?value wikibase:sitelinks ?rankMeasure"), query);
            assertTrue(query.contains("?rankMeasure >= 20"), query);
            assertTrue(query.contains("?rankMeasure < 40"), query);
            // The band FILTER is the whole difference between sibling units, so a log
            // showing anything but this query cannot tell them apart.
            assertEquals(query, request);
        }
    }

    @Test void siblingBandsDoNotInheritEachOthersRestriction() throws Exception {
        List<String> issued = new ArrayList<>();
        try (WikidataSparqlClient client = recording(issued)) {
            RuleNode shared = films();
            new RankBandWorkUnit(shared, 20, 40, client).execute();
            new RankBandWorkUnit(shared, 0, 20, client).execute();

            assertTrue(issued.get(1).contains("?rankMeasure < 20"), issued.get(1));
            assertTrue(!issued.get(1).contains("?rankMeasure < 40"),
                       "the second band must not carry the first band's ceiling:\n"
                               + issued.get(1));
        }
    }
}
