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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ranked membership scan is walked in descending bands and STOPS once the limit is
 * reached.
 *
 * <p>The stop is the whole point of ordering the bands: the limit asks for the top N by
 * the ranking measure, so once N distinct members are in hand every remaining band holds
 * only lower-ranked ones and fetching them is pure waste — for the movies case, the
 * difference between the first few bands and a scan of 47,639 candidates.
 *
 * <p>Nothing about the RESULT reveals a missing stop: scanning every band returns the
 * same top N after truncation, just far more slowly. So the bands actually issued are
 * what has to be asserted.
 */
class RuleTreeExtractorBandedBackboneTest {

    private static final Pattern BAND_FLOOR = Pattern.compile("\\?rankMeasure >= (\\d+)");

    private static RuleNode rankedFilms(int limit) {
        RuleNode node = new RuleNode("Movies", "movies");
        node.sourceQid("Q11424");
        node.propertyPid("P31");
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(limit);
        node.rankBySitelinks(true);

        // An inlineable entity-list field is what puts the load on the backbone path
        // at all — without one, membership is a single plain query and never banded.
        RuleIncludedField locations = new RuleIncludedField(
                "locations", "P840", "narrative location",
                RuleIncludedField.FieldKind.ENTITY, true);
        locations.collection(true);
        locations.direction(RuleDirection.ROOT_TO_ITEM);
        node.addIncludedField(locations);
        return node;
    }

    /** A client whose top band [64,∞) yields two films and every lower band yields none,
     *  recording the floor of each band it is asked for. */
    private static WikidataSparqlClient bandRecording(List<Long> floors) {
        return new WikidataSparqlClient("test") {
            @Override public List<WikidataBinding> query(String sparql) {
                Matcher matcher = BAND_FLOOR.matcher(sparql);
                if (!matcher.find()) {
                    return List.of();
                }
                long from = Long.parseLong(matcher.group(1));
                floors.add(from);
                return from < 64 ? List.of() : List.of(
                        row("Q11", "First film"),
                        row("Q12", "Second film"));
            }
        };
    }

    private static WikidataBinding row(String qid, String label) {
        return new WikidataBinding(Map.of(
                "value", "http://www.wikidata.org/entity/" + qid,
                "valueLabel", label));
    }

    private static FakeWikidataApiClient films() {
        return new FakeWikidataApiClient()
                .entity("Q11", "First film")
                .entity("Q12", "Second film");
    }

    @Test
    void lowerBandsAreNotScannedOnceTheLimitIsReached() throws Exception {
        List<Long> floors = new ArrayList<>();
        try (WikidataSparqlClient client = bandRecording(floors)) {
            List<WikidataDynamicObject> members = new RuleTreeExtractor(client)
                    .api(films())
                    .load(rankedFilms(2), 0);

            assertEquals(2, members.size());
            assertEquals(List.of(64L), floors,
                         "the top band already satisfied the limit — every band below it "
                                 + "holds only lower-ranked members and must not be asked for");
        }
    }

    /** The control: the stop is conditional on the limit, not an unconditional
     *  first-band-only scan, which would silently under-fill every large class. */
    @Test
    void bandsBelowTheTopAreScannedWhileTheLimitIsUnmet() throws Exception {
        List<Long> floors = new ArrayList<>();
        try (WikidataSparqlClient client = bandRecording(floors)) {
            new RuleTreeExtractor(client).api(films()).load(rankedFilms(5), 0);

            assertTrue(floors.size() > 1, "the scan stopped before the limit was met");
            assertEquals(List.of(64L, 32L, 16L, 8L, 4L, 2L, 1L, 0L), floors,
                         "an unmet limit walks every band, descending, down to zero");
        }
    }
}
