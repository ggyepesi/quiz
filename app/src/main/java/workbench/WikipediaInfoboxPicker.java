package workbench;

import wikidata.explore.query.logical.DiscoverWikipediaInfoboxQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;
import work.Query;
import work.QueryContext;

import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Shared Transform/ModelBuilder picker for native Wikipedia Infobox values. */
public final class WikipediaInfoboxPicker {
    private static final int CACHE_ENTRIES = 24;
    private static final Map<CacheKey, TableQueryResult> CACHE =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
                        @Override protected boolean removeEldestEntry(
                                Map.Entry<CacheKey, TableQueryResult> eldest) {
                            return size() > CACHE_ENTRIES;
                        }
                    });

    private WikipediaInfoboxPicker() { }

    public static void findForEntities(Component parent, SwingQueryRunner runner,
            List<String> qids, Consumer<String> selected) {
        run(parent, runner, new DiscoverWikipediaInfoboxQuery(qids), selected);
    }
    public static void findByType(Component parent, SwingQueryRunner runner,
            String typeQid, int sampleSize, Consumer<String> selected) {
        run(parent, runner, new DiscoverWikipediaInfoboxQuery(typeQid, sampleSize), selected);
    }
    private static void run(Component parent, SwingQueryRunner runner,
            DiscoverWikipediaInfoboxQuery discovery, Consumer<String> selected) {
        boolean singleArticle = discovery.singleArticle();
        // The key is the sample the query will actually read, so the cap is stated once.
        CacheKey key = new CacheKey(discovery.seeds(), discovery.typeQid(),
                discovery.seeds().isEmpty() ? discovery.sampleSize() : 0);
        SourceDiscoveryPicker.run(parent, runner, cached(key, discovery),
                new SourceDiscoveryPicker.Spec<TableQueryResult>(
                        "Observed Wikipedia infobox values",
                        singleArticle
                                ? "Values actually found in the selected article. Choose "
                                + "the one that supplies this field; <b>Examples</b> is what it "
                                + "held there."
                                // Categories rank least-shared first and parameters rank the
                                // other way. A reader who just used that picker will ask why:
                                // a category NAMES its value, so one everybody carries cannot
                                // be naming something that varies — while a parameter is a
                                // SLOT whose value varies, so the slot every article fills is
                                // the one worth reading.
                                : "Values actually found in the sampled articles, <b>most "
                                + "widely filled first</b>: an infobox value is a slot, so one that "
                                + "every article fills is the one most likely to supply this "
                                + "field for every member. <b>Have</b> is how many carry it. "
                                + "Choose the value that supplies this field.",
                        "No native Wikipedia infobox values were found for this sample.",
                        "Use selected value",
                        result -> SourceDiscoveryPicker.infoboxRows(result == null
                                ? List.of() : result.rows())),
                choice -> { if (selected != null) selected.accept(choice.value()); },
                () -> { if (selected != null) selected.accept(null); });
    }

    /** One application-session answer for one exact discovery question. The picker owns
     * this result because ModelBuilder and TransformApp both ask through this picker; the
     * datasource query remains the single uncached acquisition path beneath it. */
    static Query<TableQueryResult> cached(CacheKey key,
            Query<TableQueryResult> acquisition) {
        return new Query<>() {
            @Override public String purpose() { return acquisition.purpose(); }
            @Override public String skeleton() { return acquisition.skeleton(); }
            @Override public String queryType() { return acquisition.queryType(); }
            @Override public String description() { return acquisition.description(); }
            @Override public Map<String, String> parameters() {
                return acquisition.parameters();
            }
            @Override public TableQueryResult execute(QueryContext context) throws Exception {
                TableQueryResult known = CACHE.get(key);
                if (known != null) {
                    context.message("Load cached Wikipedia infobox discovery for "
                            + key.description() + ": " + known.size()
                            + " value(s). No Wikidata or Wikipedia request is made.\n");
                    return known;
                }
                TableQueryResult acquired = acquisition.execute(context);
                if (acquired != null) CACHE.put(key, acquired);
                return acquired;
            }
            @Override public int rowCount(TableQueryResult result) {
                return acquisition.rowCount(result);
            }
            @Override public String summary(TableQueryResult result) {
                return acquisition.summary(result);
            }
        };
    }

    static void clearCacheForTests() { CACHE.clear(); }

    record CacheKey(List<String> qids, String typeQid, int sampleSize) {
        CacheKey {
            qids = qids == null ? List.of() : List.copyOf(qids);
            typeQid = typeQid == null ? "" : typeQid.trim();
            sampleSize = Math.max(0, sampleSize);
        }

        String description() {
            return qids.isEmpty()
                    ? "type " + typeQid + ", sample " + sampleSize
                    : String.join(", ", qids);
        }
    }
}
