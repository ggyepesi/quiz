package wikidata.explore.query.template.sparql;

import java.util.Collection;

/**
 * Builds a SPARQL {@code VALUES ?var { wd:Q1 wd:Q2 … }} inline binding from QIDs —
 * the clause that pins a query to a known set: membership targets, a pool batch,
 * or an allowed-value set (e.g. the Oscar categories). Centralized so every
 * hand-assembled VALUES clause reads and cleans QIDs the same way and stays
 * deterministic.
 *
 * <p>Pinning both ends of a join with two VALUES clauses ({@code VALUES ?e} +
 * {@code VALUES ?value}) is the key determinism lever: it bounds the intermediate
 * result so the query reliably COMPLETES rather than soft-timing-out on WDQS and
 * returning a different partial row set each run.
 *
 * <p>An empty set yields {@code VALUES ?var { }} — a deliberate empty binding
 * (zero rows), not a syntax error.
 */
public final class SparqlValues {

    private SparqlValues() {}

    /** {@code VALUES ?<var> { wd:Q1 wd:Q2 … }} (no trailing newline). */
    public static String clause(String var, Collection<String> qids) {
        StringBuilder sb = new StringBuilder();
        if (qids != null) {
            for (String q : qids) {
                String cq = cleanQid(q);
                if (!cq.isEmpty()) {
                    sb.append("wd:").append(cq).append(' ');
                }
            }
        }
        return "VALUES ?" + var + " { " + sb.toString().trim() + " }";
    }

    /** Strip a {@code wd:} prefix or entity-URL to the bare {@code Q…} id. */
    public static String cleanQid(String qid) {
        if (qid == null) {
            return "";
        }
        String s = qid.trim();
        if (s.startsWith("wd:")) {
            s = s.substring(3);
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        return s.trim();
    }
}
