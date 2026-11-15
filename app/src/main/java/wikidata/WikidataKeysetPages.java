package wikidata;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.BatchProgress;
import batch.WorkDescriptor;
import batch.WorkUnit;
import work.CancellationToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads one complete population through ordered, bounded WDQS keyset pages (R18).
 *
 * <p>A short non-empty page is progress, never proof of completion: a valid-JSON
 * soft-timeout may return only a prefix. The next request starts after the last row
 * actually received, and rows must arrive strictly ordered by that key. An empty page is
 * confirmed by a separate one-row request before the population is complete. Each page
 * is its own logged batch unit, so the query log shows every request that was issued.
 *
 * <p>A heavy page may be halved at its cursor, keeping the proven smaller size for later
 * pages — but only when the caller says so. Halving shrinks the rows returned, not the
 * work WDQS does to order the population; a caller that can make the population itself
 * smaller (fewer VALUES targets) declines halving and lets the failure escape, so its own
 * batch unit splits first.
 */
public final class WikidataKeysetPages {
    public static final int PAGE_SIZE = 1_000;

    private WikidataKeysetPages() { }

    /** One keyset-paged population: how to request a page and read its rows. */
    public interface Population<R> {
        /** The page after {@code cursor} (blank for the first), ordered by the cursor
         *  expression and limited to {@code limit} rows; see {@link #after}. */
        String request(String cursor, int limit);

        /** The row's ordered key, the value the next page's cursor continues after. */
        String cursor(WikidataBinding row);

        /** The row's result, or null when the row advances the cursor but keeps nothing. */
        R value(WikidataBinding row);

        WorkDescriptor descriptor(String cursor, int limit);
    }

    public record Result<R>(List<R> values, int pages, int finalLimit) { }

    /** The FILTER line that continues after {@code cursor}; empty for the first page. */
    public static String after(String expression, String cursor) {
        if (cursor == null || cursor.isBlank()) return "";
        return "  FILTER(" + expression + " > \""
                + cursor.replace("\\", "\\\\").replace("\"", "\\\"") + "\")\n";
    }

    public static <R> Result<R> read(
            WikidataSparqlClient client,
            Population<R> population,
            boolean halveHeavyPages,
            BatchProgress progress,
            CancellationToken cancellation) throws Exception {
        if (client == null) throw new IllegalArgumentException("A SPARQL client is required");
        CancellationToken token = cancellation == null ? new CancellationToken() : cancellation;
        List<R> values = new ArrayList<>();
        String cursor = "";
        int limit = PAGE_SIZE;
        int pages = 0;
        while (true) {
            Page<R> page = page(client, population, cursor, limit, halveHeavyPages,
                    progress, token);
            pages++;
            limit = page.limit();
            if (page.rows() == 0) {
                Page<R> probe = page(client, population, cursor, 1, halveHeavyPages,
                        progress, token);
                pages++;
                if (probe.rows() == 0) break;
                page = probe;
            }
            values.addAll(page.values());
            if (page.last().isBlank() || page.last().equals(cursor)) {
                throw new IllegalStateException("Keyset paging made no progress");
            }
            cursor = page.last();
        }
        return new Result<>(List.copyOf(values), pages, limit);
    }

    private static <R> Page<R> page(
            WikidataSparqlClient client, Population<R> population, String cursor,
            int limit, boolean halveHeavyPages, BatchProgress progress,
            CancellationToken token) throws Exception {
        List<Page<R>> result = new ArrayList<>(1);
        new BatchExecutor<Page<R>>(BatchPolicy.defaults().withResume(false),
                progress, WikidataBatchFailureClassifier.INSTANCE,
                token, BatchCheckpointStore.NONE)
                .run(List.of(new PageUnit<>(client, population, cursor, limit,
                                halveHeavyPages)),
                        (descriptor, page) -> result.add(page));
        return result.getFirst();
    }

    private record Page<R>(List<R> values, String last, int rows, int limit) { }

    private static final class PageUnit<R> implements WorkUnit<Page<R>> {
        private final WikidataSparqlClient client;
        private final Population<R> population;
        private final String cursor;
        private final int limit;
        private final boolean halveHeavyPages;

        private PageUnit(WikidataSparqlClient client, Population<R> population,
                String cursor, int limit, boolean halveHeavyPages) {
            this.client = client;
            this.population = population;
            this.cursor = cursor == null ? "" : cursor;
            this.limit = Math.max(1, limit);
            this.halveHeavyPages = halveHeavyPages;
        }

        @Override public WorkDescriptor descriptor() {
            return population.descriptor(cursor, limit);
        }

        @Override public String request() { return population.request(cursor, limit); }

        @Override public Page<R> execute() throws Exception {
            List<R> values = new ArrayList<>();
            String last = cursor;
            int rows = 0;
            for (WikidataBinding row : client.query(request())) {
                String key = population.cursor(row);
                if (key == null || key.isBlank()
                        || (!last.isBlank() && key.compareTo(last) <= 0)) {
                    throw new IllegalStateException(
                            "Keyset page is not strictly ordered by its cursor");
                }
                last = key;
                rows++;
                R value = population.value(row);
                if (value != null) values.add(value);
            }
            return new Page<>(List.copyOf(values), last, rows, limit);
        }

        @Override public List<? extends WorkUnit<Page<R>>> split() {
            if (!halveHeavyPages || limit <= 1) return List.of();
            // A keyset page is sequential: its successor cursor is known only after
            // this prefix succeeds. Replacing one heavy page with its first smaller
            // prefix is therefore the complete split; the reader schedules the
            // successor from the returned last row.
            return List.of(new PageUnit<>(client, population, cursor,
                    Math.max(1, limit / 2), true));
        }
    }
}
