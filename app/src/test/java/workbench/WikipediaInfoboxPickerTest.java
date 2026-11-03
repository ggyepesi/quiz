package workbench;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import wikidata.explore.query.result.TableQueryResult;
import work.Query;
import work.QueryContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reopening infobox discovery while configuring several fields must not download the
 * same sample again; a different sample remains a different question. */
class WikipediaInfoboxPickerTest {

    @AfterEach void clear() { WikipediaInfoboxPicker.clearCacheForTests(); }

    @Test void theSameDiscoveryQuestionReusesItsCompletedResult() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Query<TableQueryResult> acquisition = acquisition(requests, "Rendered.Group");
        var key = new WikipediaInfoboxPicker.CacheKey(List.of(), "Q11344", 8);

        TableQueryResult first = WikipediaInfoboxPicker.cached(key, acquisition)
                .execute(new QueryContext());
        TableQueryResult second = WikipediaInfoboxPicker.cached(key, acquisition)
                .execute(new QueryContext());

        assertEquals(1, requests.get());
        assertEquals(first, second);
    }

    @Test void aDifferentSampleDoesNotReuseTheEarlierAnswer() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Query<TableQueryResult> acquisition = acquisition(requests, "Rendered.Group");

        WikipediaInfoboxPicker.cached(
                new WikipediaInfoboxPicker.CacheKey(List.of(), "Q11344", 8), acquisition)
                .execute(new QueryContext());
        WikipediaInfoboxPicker.cached(
                new WikipediaInfoboxPicker.CacheKey(List.of(), "Q11344", 12), acquisition)
                .execute(new QueryContext());

        assertEquals(2, requests.get());
    }

    /** The cache key is read off the query, so it names the sample the query reads. */
    @Test void theDiscoveryStatesItsOwnCappedSample() {
        List<String> selected = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(i -> "Q" + i).toList();
        var discovery = new wikidata.explore.query.logical
                .DiscoverWikipediaInfoboxQuery(selected);
        var sampled = new wikidata.explore.query.logical
                .DiscoverWikipediaInfoboxQuery("Q11344", 40);

        assertEquals(selected.subList(0, 12), discovery.seeds());
        assertEquals(12, sampled.sampleSize());
    }

    private static Query<TableQueryResult> acquisition(
            AtomicInteger requests, String value) {
        return new Query<>() {
            @Override public String purpose() { return "Discover"; }
            @Override public String skeleton() { return "sample -> values"; }
            @Override public Map<String, String> parameters() { return Map.of(); }
            @Override public TableQueryResult execute(QueryContext context) {
                requests.incrementAndGet();
                return new TableQueryResult(List.of("Value"), List.of(List.of(value)));
            }
            @Override public int rowCount(TableQueryResult result) { return result.size(); }
        };
    }
}
