package wikidata.explore.transform;

import batch.WorkDescriptor;
import wikidata.WikidataBinding;
import wikidata.WikidataIds;
import wikidata.WikidataKeysetPages;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.model.EntityBound;
import work.CancellationToken;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a relational bound through the shared ordered keyset pages (R18), ordered by
 * the object IRI. The bound's VALUES are its definition, not a partition the caller may
 * narrow, so a heavy page is halved at its cursor.
 */
final class RelationalObjectPopulationLoader {
    private RelationalObjectPopulationLoader() { }

    static List<String> load(WikidataSparqlClient client, EntityBound bound,
            GenerationLog log, CancellationToken cancellation) throws Exception {
        if (client == null) {
            throw new IllegalArgumentException("A SPARQL client is required");
        }
        requireRelational(bound);
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        LinkedHashMap<String, String> qids = new LinkedHashMap<>();
        try (GenerationLog.Group group = sink.group(
                "Resolve objects matching " + describe(bound) + " in bounded pages")) {
            WikidataKeysetPages.Result<String> result = WikidataKeysetPages.read(
                    client, new BoundObjects(bound), true, group.batchProgress(), cancellation);
            result.values().forEach(qid -> qids.putIfAbsent(qid, qid));
            group.message("Resolved " + qids.size() + " distinct object(s) in "
                    + result.pages() + " page request(s); final page size "
                    + result.finalLimit() + ".\n");
        }
        return List.copyOf(qids.keySet());
    }

    static String query(EntityBound bound, String cursor, int limit) {
        requireRelational(bound);
        StringBuilder q = new StringBuilder("SELECT DISTINCT ?value WHERE {\n")
                .append("  ?value wdt:").append(bound.relationPid())
                .append(bound.includeDescendants() ? "/wdt:P279* " : " ")
                .append("?valueKind .\n  VALUES ?valueKind {");
        for (String qid : bound.qids()) q.append(" wd:").append(qid);
        q.append(" }\n  FILTER(STRSTARTS(STR(?value), ")
                .append("\"http://www.wikidata.org/entity/Q\"))\n")
                .append(WikidataKeysetPages.after("STR(?value)", cursor));
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

    private record BoundObjects(EntityBound bound)
            implements WikidataKeysetPages.Population<String> {
        @Override public String request(String cursor, int limit) {
            return query(bound, cursor, limit);
        }

        @Override public String cursor(WikidataBinding row) { return row.value("value"); }

        @Override public String value(WikidataBinding row) {
            String qid = row.qid("value");
            return WikidataIds.isQid(qid) ? qid : null;
        }

        @Override public WorkDescriptor descriptor(String cursor, int limit) {
            String targets = String.join(",", bound.qids());
            String position = cursor.isBlank() ? "start" : cursor;
            return new WorkDescriptor("relational-object-page",
                    bound.relationPid() + ":" + targets + ":" + position + ":" + limit,
                    "Objects matching " + describe(bound) + " after " + position,
                    Map.of("relation", bound.relationPid(), "targets", targets,
                            "cursor", cursor, "limit", String.valueOf(limit)));
        }
    }
}
