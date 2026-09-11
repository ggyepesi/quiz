package wikidata.explore.transform;

import batch.BatchCheckpointStore;
import batch.BatchExecutor;
import batch.BatchPolicy;
import batch.WorkDescriptor;
import batch.WorkUnit;
import wikidata.WikidataBatchFailureClassifier;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.EntityBound;
import work.CancellationToken;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a relational bound through ordered, bounded keyset pages (R18).
 *
 * <p>A short non-empty page is progress, never proof of completion: a valid-JSON
 * soft-timeout may return only a prefix. The next request starts after the last row
 * actually received. A page that times out is retried at half the size, preserving its
 * cursor; subsequent pages keep the proven smaller size. An empty page is confirmed by
 * a separate one-row request before the population is declared complete.
 */
final class RelationalObjectPopulationLoader {
    private static final int PAGE_SIZE = 1_000;

    private RelationalObjectPopulationLoader() { }

    static List<String> load(WikidataSparqlClient client, EntityBound bound,
            GenerationLog log, CancellationToken cancellation) throws Exception {
        if (client == null) {
            throw new IllegalArgumentException("A SPARQL client is required");
        }
        requireRelational(bound);
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        CancellationToken token = cancellation == null
                ? new CancellationToken() : cancellation;
        LinkedHashMap<String, String> qids = new LinkedHashMap<>();
        String cursor = "";
        int pageLimit = PAGE_SIZE;
        int cursorAdvances = 0;
        try (GenerationLog.Group group = sink.group(
                "Resolve objects matching " + describe(bound) + " in bounded pages")) {
            while (true) {
                Page page = executePage(client, bound, cursor, pageLimit, group, token);
                pageLimit = page.limit();
                if (page.rows() == 0) {
                    Page probe = executePage(client, bound, cursor, 1, group, token);
                    if (probe.rows() == 0) break;
                    page = probe;
                }
                page.qids().forEach(qid -> qids.putIfAbsent(qid, qid));
                if (page.lastValue().isBlank() || page.lastValue().equals(cursor)) {
                    throw new IllegalStateException("Relational object paging made no progress");
                }
                cursor = page.lastValue();
                cursorAdvances++;
            }
            group.message("Resolved " + qids.size() + " distinct object(s) in "
                    + cursorAdvances + " cursor advance(s); final page size "
                    + pageLimit + ".\n");
        }
        return List.copyOf(qids.keySet());
    }

    private static Page executePage(WikidataSparqlClient client, EntityBound bound,
            String cursor, int limit, GenerationLog log, CancellationToken token)
            throws Exception {
        List<Page> result = new java.util.ArrayList<>(1);
        new BatchExecutor<Page>(BatchPolicy.defaults().withResume(false),
                log.batchProgress(), WikidataBatchFailureClassifier.INSTANCE,
                token, BatchCheckpointStore.NONE)
                .run(List.of(new PageUnit(client, bound, cursor, limit)),
                        (descriptor, page) -> result.add(page));
        return result.getFirst();
    }

    static String query(EntityBound bound, String cursor, int limit) {
        requireRelational(bound);
        StringBuilder q = new StringBuilder("SELECT DISTINCT ?value WHERE {\n")
                .append("  ?value wdt:").append(bound.relationPid())
                .append(bound.includeDescendants() ? "/wdt:P279* " : " ")
                .append("?valueKind .\n  VALUES ?valueKind {");
        for (String qid : bound.qids()) q.append(" wd:").append(qid);
        q.append(" }\n  FILTER(STRSTARTS(STR(?value), ")
                .append("\"http://www.wikidata.org/entity/Q\"))\n");
        if (cursor != null && !cursor.isBlank()) {
            q.append("  FILTER(STR(?value) > \"").append(cursor).append("\")\n");
        }
        return q.append("}\nORDER BY STR(?value)\nLIMIT ")
                .append(Math.max(1, limit)).toString();
    }

    private static void requireRelational(EntityBound bound) {
        if (bound == null || bound.kind() != EntityBound.Kind.RELATION) {
            throw new IllegalArgumentException("A relational object bound is required");
        }
    }

    private static String describe(EntityBound bound) {
        return bound.relationPid() + (bound.includeDescendants() ? "/P279*" : "")
                + " = " + String.join(", ", bound.qids());
    }

    private record Page(List<String> qids, String lastValue, int rows, int limit) { }

    private static final class PageUnit implements WorkUnit<Page> {
        private final WikidataSparqlClient client;
        private final EntityBound bound;
        private final String cursor;
        private final int limit;

        private PageUnit(WikidataSparqlClient client, EntityBound bound,
                String cursor, int limit) {
            this.client = client;
            this.bound = bound;
            this.cursor = cursor == null ? "" : cursor;
            this.limit = limit;
        }

        @Override public WorkDescriptor descriptor() {
            String targets = String.join(",", bound.qids());
            String position = cursor.isBlank() ? "start" : cursor;
            return new WorkDescriptor("relational-object-page",
                    bound.relationPid() + ":" + targets + ":" + position + ":" + limit,
                    "Objects matching " + describe(bound) + " after " + position,
                    Map.of("relation", bound.relationPid(), "targets", targets,
                            "cursor", cursor, "limit", String.valueOf(limit)));
        }

        @Override public String request() { return query(bound, cursor, limit); }

        @Override public Page execute() throws Exception {
            LinkedHashMap<String, String> qids = new LinkedHashMap<>();
            String last = "";
            int rows = 0;
            for (WikidataBinding binding : client.query(request())) {
                String value = binding.value("value");
                if (value == null || value.isBlank()) continue;
                rows++;
                last = value;
                String qid = binding.qid("value");
                if (WikidataIds.isQid(qid)) qids.putIfAbsent(qid, qid);
            }
            return new Page(List.copyOf(qids.keySet()), last, rows, limit);
        }

        @Override public List<? extends WorkUnit<Page>> split() {
            if (limit <= 1) return List.of();
            // A keyset page is sequential: its successor cursor is known only after
            // this prefix succeeds. Replacing one heavy page with its first smaller
            // prefix is therefore the complete split; the outer loop schedules the
            // successor from the returned last row.
            return List.of(new PageUnit(client, bound, cursor,
                    Math.max(1, limit / 2)));
        }
    }
}
