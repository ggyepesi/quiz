package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the companion-set for a {@link CompanionMatcher}: the statements
 * {@code ?subject companionProperty [ps=value, pq:roleQualifier=role]} whose
 * value is one of the given values, as {@code (subjectQid, valueQid, roleQid)}
 * tuples.
 *
 * <p>Value-anchored: the value set is usually small and already in hand (e.g. the
 * award categories), whereas the subjects (every possible winner) are not — so we
 * pin {@code ?value} with VALUES and let the subject range free. Oscars:
 * {@code ?winner P166 [ps=category ∈ the categories, pq:P1686=for-work]} — the
 * wins. The role qualifier is OPTIONAL, and the emitted role is
 * {@code COALESCE(role, subject)}: a win with no for-work (recorded on the work
 * itself, e.g. Best Picture) keys back to the work (= the subject).
 */
public final class CompanionLoader {

    // A handful of categories per query — a value-anchored VALUES join that stays
    // small and reliable. loadWithSplit halves any batch that overruns WDQS, so a
    // transient timeout costs only the half that failed, not the whole load; that
    // robustness is what let this drop from one query per category to batches.
    private static final int BATCH = 8;

    private CompanionLoader() {}

    public static Set<List<String>> load(
            Collection<String> valueQids,
            String companionProperty,
            String roleQualifier,
            WikidataSparqlClient client,
            GenerationLog log) {

        Set<List<String>> out = new HashSet<>();
        if (valueQids == null || client == null
                || companionProperty == null || !companionProperty.matches("(?i)P\\d+")
                || roleQualifier == null || !roleQualifier.matches("(?i)P\\d+")) {
            return out;
        }

        List<String> values = new ArrayList<>(new LinkedHashSet<>(
                valueQids.stream().filter(q -> q != null && WikidataIds.isQid(q)).toList()));
        if (values.isEmpty()) {
            return out;
        }

        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        // One collapsible group over all per-value batches.
        try (GenerationLog.Group g = sink.group("Companion load "
                + companionProperty + "/" + roleQualifier
                + " (" + values.size() + " values)")) {
            int total = (values.size() + BATCH - 1) / BATCH;
            int n = 0;
            for (int from = 0; from < values.size(); from += BATCH) {
                if (Thread.currentThread().isInterrupted()) {
                    g.message("Companion load cancelled.\n");
                    break;
                }
                List<String> batch = new ArrayList<>(values.subList(
                        from, Math.min(from + BATCH, values.size())));
                loadWithSplit(batch, companionProperty, roleQualifier,
                        client, g, out, (++n) + "/" + total);
            }
            g.message("Companion load " + companionProperty + "/" + roleQualifier
                    + " -> " + out.size() + " (subject,value,role) tuples\n");
        }
        return out;
    }

    // Fetch one batch of values; on a WDQS overrun (timeout/502) halve it and retry
    // each half, down to a single value — so a fat batch never drops every category
    // it held. The started/done/failed node makes each attempt visible in the log.
    private static void loadWithSplit(
            List<String> batch, String prop, String roleQual,
            WikidataSparqlClient client, GenerationLog log,
            Set<List<String>> out, String label) {

        if (batch.isEmpty() || Thread.currentThread().isInterrupted()) {
            return;
        }
        String query = buildQuery(batch, prop, roleQual);
        int before = out.size();
        String title = batch.size() == 1
                ? batch.get(0) : label + " (" + batch.size() + " values)";
        GenerationLog.Running running = log.subqueryStarted(title, query);
        try {
            for (WikidataBinding row : client.query(query)) {
                String subj = row.qid("subj");
                String value = row.qid("value");
                String role = row.qid("role");
                // COALESCE(role, subject): a companion with no role qualifier
                // (recorded on the value's own subject) keys back to the subject.
                if (role == null || role.isBlank()) {
                    role = subj;
                }
                if (subj != null && !subj.isBlank()
                        && value != null && !value.isBlank()
                        && role != null && !role.isBlank()) {
                    out.add(List.of(subj, value, role));
                }
            }
            running.done((out.size() - before) + " companions");
        } catch (Exception e) {
            running.failed(e.getMessage());
            if (e instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
            if (batch.size() > 1) {
                int mid = batch.size() / 2;
                loadWithSplit(new ArrayList<>(batch.subList(0, mid)),
                        prop, roleQual, client, log, out, label + "a");
                loadWithSplit(new ArrayList<>(batch.subList(mid, batch.size())),
                        prop, roleQual, client, log, out, label + "b");
            }
        }
    }

    // ?subject companionProperty [ps=value ∈ values, pq:roleQualifier=role?]
    static String buildQuery(List<String> values, String prop, String roleQual) {
        StringBuilder vals = new StringBuilder();
        for (String q : values) {
            vals.append("wd:").append(q).append(" ");
        }
        return "SELECT ?subj ?value ?role WHERE {\n"
                + "  VALUES ?value { " + vals.toString().trim() + " }\n"
                + "  ?subj p:" + prop + " ?st .\n"
                + "  ?st ps:" + prop + " ?value .\n"
                + "  OPTIONAL { ?st pq:" + roleQual + " ?role . }\n"
                + "}\n";
    }
}
