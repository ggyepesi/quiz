package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.log.LogStep;
import wikidata.explore.query.result.TableQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * General relation explorer: given one entity, lists ALL of its outgoing and
 * incoming direct-claim relations with a count and a sample example each — so you
 * can see, for any domain, how the entity is connected (e.g. an Oscar category's
 * incoming "award received ← P166" and "nominated for ← P1411"). Replaces the old
 * fixed 5-probe Greek-myth battery, which returned nothing outside that domain.
 *
 * <p>Runs TWO queries rather than one UNION: a UNION of two aggregate subqueries
 * plus outer label joins makes Blazegraph pick a plan that times out (~65s) even
 * though each side alone is ~1s. The incoming side is the costly one (an unbound
 * predicate scan over everything pointing at the entity), so it keeps the property
 * label INSIDE the grouped subquery and resolves only one SAMPLEd example per
 * relation in the outer OPTIONAL — never a label per member (that was the timeout).
 * Full member lists are fetched on demand by {@link RelationMembersQuery} with the
 * predicate BOUND (fast), only when the user adds a relation's members as seeds.
 */
public class ExploreEntityQuery implements Query<TableQueryResult> {

    public static final List<String> COLUMNS =
            List.of("Direction", "PID", "Relation", "Count", "Example", "Kind");

    private final String qid;
    private final int limit;
    private final boolean outgoingOnly;

    public ExploreEntityQuery(String qid, int limit) {
        this(qid, limit, false);
    }

    /** {@code outgoingOnly} skips the incoming-relation scan — the entity's own properties
     *  are what a property picker (e.g. curate "find wikidata source") wants, and the
     *  incoming side is the slow, unbounded one (everything that points at the entity). */
    public ExploreEntityQuery(String qid, int limit, boolean outgoingOnly) {
        this.qid = qid == null ? "" : qid.trim();
        this.limit = Math.max(1, limit);
        this.outgoingOnly = outgoingOnly;
    }

    @Override
    public String purpose() {
        return "Explore entity relations";
    }

    @Override
    public String skeleton() {
        return "all outgoing + incoming direct-claim relations -> count + example";
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("qid", qid, "limit", String.valueOf(limit));
    }

    // Outgoing: the entity's own statements (cheap). Incoming: everything pointing
    // at it (heavier) — keep the label inside the grouped subquery, resolve only
    // the sample example outside.
    private String sparql(boolean incoming) {
        String e = "wd:" + qid;
        // Outgoing keeps literal values too (no isIRI filter), so literal properties
        // (population, area, dates, images) show up — the Kind column then tells them apart
        // from entity relations. Incoming ?x is always an entity (a subject pointing at us).
        String grouped = incoming
                ? "    SELECT ?p ?pl (COUNT(DISTINCT ?x) AS ?n) (SAMPLE(?x) AS ?ex) WHERE {\n"
                + "      ?x ?dp " + e + " . ?p wikibase:directClaim ?dp .\n"
                + "      ?p rdfs:label ?pl . FILTER(LANG(?pl) = \"en\")\n"
                + "    } GROUP BY ?p ?pl"
                : "    SELECT ?p ?pl (COUNT(DISTINCT ?x) AS ?n) (SAMPLE(?x) AS ?ex) WHERE {\n"
                + "      " + e + " ?dp ?x . ?p wikibase:directClaim ?dp .\n"
                + "      ?p rdfs:label ?pl . FILTER(LANG(?pl) = \"en\")\n"
                + "    } GROUP BY ?p ?pl";
        // Return the raw sample value ?ex (for Kind classification) plus its label when the
        // value is an entity (so the example reads as a name, not a bare QID).
        return "SELECT ?p ?pl ?n ?ex ?exLabel WHERE {\n"
                + "  {\n" + grouped + "\n  }\n"
                + "  OPTIONAL { ?ex rdfs:label ?exLabel . FILTER(LANG(?exLabel) = \"en\") }\n"
                + "} ORDER BY DESC(?n) LIMIT " + limit;
    }

    @Override
    public TableQueryResult execute(QueryContext context) throws Exception {
        return context.step(
                "Explore " + qid,
                "SPARQL",
                null,
                Map.of("qid", qid),
                step -> {
                    List<List<Object>> rows = new ArrayList<>();
                    runDirection(context, step, false, rows); // outgoing →
                    if (!outgoingOnly) {
                        runDirection(context, step, true, rows);  // incoming ←
                    }
                    // Merge sort by count desc across both directions.
                    rows.sort((a, b) -> Integer.compare(
                            num(b.get(3)), num(a.get(3))));
                    step.summary(rows.size() + " relations");
                    return new TableQueryResult(COLUMNS, rows);
                });
    }

    private void runDirection(QueryContext context, LogStep step,
                              boolean incoming, List<List<Object>> rows) throws Exception {
        String sparql = sparql(incoming);
        step.subquery(incoming ? "Incoming relations" : "Outgoing relations",
                sparql, null);
        for (WikidataBinding b : context.sparql(Datasource.WIKIDATA).query(sparql)) {
            String pid = b.qid("p");
            if (pid == null || !WikidataIds.isPid(pid)) {
                continue;
            }
            String label = b.value("pl");
            String count = b.value("n");
            String[] kindEx = classify(b.value("ex"), b.value("exLabel"));
            rows.add(new ArrayList<>(List.of(
                    incoming ? "← in" : "→ out",
                    pid,
                    label == null ? pid : label,
                    count == null ? "0" : count,
                    kindEx[1],    // Example (display)
                    kindEx[0]))); // Kind
        }
    }

    private static final java.util.regex.Pattern NUMBER =
            java.util.regex.Pattern.compile("[+-]?\\d+(\\.\\d+)?");
    private static final java.util.regex.Pattern DATE =
            java.util.regex.Pattern.compile("[+-]?\\d{1,4}-\\d\\d-\\d\\d.*");

    /**
     * Classify a relation's sample value into a {@code {kind, exampleForDisplay}} pair.
     * Entities read as their label; images as the file name; literals as themselves,
     * tagged number / date / text. This is where the Kind column comes from.
     */
    static String[] classify(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return new String[]{"—", ""};
        }
        String r = raw.trim();
        if (r.startsWith("http://www.wikidata.org/entity/")
                || r.startsWith("https://www.wikidata.org/entity/")) {
            String tail = r.substring(r.lastIndexOf('/') + 1);
            return new String[]{"entity",
                    label != null && !label.isBlank() ? label : tail};
        }
        if (isImageUrl(r)) {
            return new String[]{"image", fileName(r)};
        }
        if (r.startsWith("http://") || r.startsWith("https://")) {
            return new String[]{"url", r};
        }
        if (DATE.matcher(r).matches()) {
            return new String[]{"date", r};
        }
        if (NUMBER.matcher(r).matches()) {
            return new String[]{"number", r};
        }
        return new String[]{"text", label != null && !label.isBlank() ? label : r};
    }

    private static boolean isImageUrl(String r) {
        String lower = r.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("commons.wikimedia.org")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".svg")
                || lower.endsWith(".gif") || lower.endsWith(".tif")
                || lower.endsWith(".tiff") || lower.endsWith(".webp");
    }

    private static String fileName(String url) {
        String tail = url.substring(url.lastIndexOf('/') + 1);
        return java.net.URLDecoder.decode(tail, java.nio.charset.StandardCharsets.UTF_8)
                .replace('_', ' ');
    }

    private static int num(Object o) {
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(TableQueryResult result) {
        return rowCount(result) + " relations";
    }
}
