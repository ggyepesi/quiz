package wikidata.explore.extract;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.api.FakeWikidataApiClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.explore.rule.RuleNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A candidate the limit rejects must leave the POOL, not just the returned list.
 *
 * <p>Discovery over-produces — a rank band returns everything in it — and every candidate
 * it sees is created in the shared registry. The save writes the registry, so trimming
 * only the returned list persisted the rejects as field-less instances: a 20,000-limit
 * movies run saved 31,630, of which 11,634 had no fields at all. In the pool those are
 * indistinguishable from real members whose fields genuinely failed to load, which is
 * exactly the confusion the extractor refuses to create everywhere else.
 */
class BackboneOvershootPoolTest {

    private static final Pattern BAND_FLOOR = Pattern.compile("\\?rankMeasure >= (\\d+)");

    private static RuleNode rankedFilms(int limit) {
        RuleNode node = new RuleNode("Movies", "movies");
        node.sourceQid("Q11424");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(limit);
        node.rankBySitelinks(true);

        RuleIncludedField locations = new RuleIncludedField(
                "locations", "P840", "narrative location",
                RuleIncludedField.FieldKind.ENTITY, true);
        locations.collection(true);
        locations.direction(RuleDirection.ROOT_TO_ITEM);
        node.addIncludedField(locations);
        return node;
    }

    /** The top band alone returns FIVE films — more than the limit of two, which is what
     *  a real band does: it holds whatever it holds. */
    private static WikidataSparqlClient overshootingTopBand() {
        return new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String sparql) {
                Matcher matcher = BAND_FLOOR.matcher(sparql);
                if (!matcher.find() || Long.parseLong(matcher.group(1)) < 64) {
                    return List.of();
                }
                List<WikidataBinding> rows = new ArrayList<>();
                for (int i = 1; i <= 5; i++) {
                    rows.add(new WikidataBinding(Map.of(
                            "value", "http://www.wikidata.org/entity/Q" + i,
                            "valueLabel", "Film " + i)));
                }
                return rows;
            }
        };
    }

    @Test void rejectedCandidatesAreNotLeftInThePool() throws Exception {
        try (WikidataSparqlClient client = overshootingTopBand()) {
            RuleTreeExtractor extractor = new RuleTreeExtractor(client)
                    .api(new FakeWikidataApiClient()
                                 .entity("Q1", "Film 1").entity("Q2", "Film 2"));

            List<WikidataDynamicObject> members = extractor.load(rankedFilms(2), 0);

            assertEquals(2, members.size(), "the returned list respects the limit");
            assertNotNull(extractor.registry().get("Q1"));
            assertNotNull(extractor.registry().get("Q2"));
            // Q3..Q5 were discovered, then rejected. The pool is what gets saved.
            assertNull(extractor.registry().get("Q3"), "a rejected candidate would be "
                    + "saved as a field-less instance");
            assertNull(extractor.registry().get("Q4"));
            assertNull(extractor.registry().get("Q5"));
            assertEquals(2, extractor.registry().values().size());
        }
    }

    /** The pool is shared across class runs, so trimming must not reach an object that
     *  was already there — deleting a live instance is worse than keeping a blank one. */
    @Test void anObjectAnotherRunAlreadyPooledSurvivesTheTrim() throws Exception {
        WikidataObjectRegistry shared = new WikidataObjectRegistry();
        WikidataDynamicObject owned = shared.getOrCreate("Q4", "Owned by an earlier run");
        owned.merge("locations", shared.getOrCreate("Q1044", "Sierra Leone"));

        try (WikidataSparqlClient client = overshootingTopBand()) {
            RuleTreeExtractor extractor = new RuleTreeExtractor(client, shared)
                    .api(new FakeWikidataApiClient()
                                 .entity("Q1", "Film 1").entity("Q2", "Film 2"));

            extractor.load(rankedFilms(2), 0);

            assertNull(shared.get("Q3"), "Q3 was created by this run and rejected");
            assertEquals(owned, shared.get("Q4"),
                         "Q4 predates this run — the SAME instance must survive, "
                                 + "references to it are still live");
            assertNotNull(owned.get("locations"), "its fields must survive too");
        }
    }
}
