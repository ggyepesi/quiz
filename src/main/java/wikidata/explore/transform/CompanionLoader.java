package wikidata.explore.transform;

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

    // One query per value (per award category): each is a small, reliable fetch
    // (~tens–hundreds of winners) and a transient WDQS timeout costs only that one
    // category, not the whole load (the per-batch catch below skips + logs it).
    private static final int BATCH = 1;

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
                valueQids.stream().filter(q -> q != null && q.matches("Q\\d+")).toList()));
        if (values.isEmpty()) {
            return out;
        }

        int total = (values.size() + BATCH - 1) / BATCH;
        int idx = 0;
        for (int from = 0; from < values.size(); from += BATCH) {
            if (Thread.currentThread().isInterrupted()) {
                if (log != null) {
                    log.message("Companion load cancelled.\n");
                }
                break;
            }
            List<String> batch = values.subList(
                    from, Math.min(from + BATCH, values.size()));
            String query = buildQuery(batch, companionProperty, roleQualifier);
            int before = out.size();
            String label = "Companion load " + (++idx) + "/" + total + " ("
                    + companionProperty + "/" + roleQualifier + ", "
                    + (batch.size() == 1 ? batch.get(0) : batch.size() + " values") + ")";
            try {
                for (WikidataBinding row : client.query(query)) {
                    String subj = row.qid("subj");
                    String value = row.qid("value");
                    String role = row.qid("role");
                    // COALESCE(role, subject): a companion with no role qualifier
                    // (the outcome recorded on the value's own subject) keys back
                    // to the subject.
                    if (role == null || role.isBlank()) {
                        role = subj;
                    }
                    if (subj != null && !subj.isBlank()
                            && value != null && !value.isBlank()
                            && role != null && !role.isBlank()) {
                        out.add(List.of(subj, value, role));
                    }
                }
                if (log != null) {
                    log.subquery(label, query, (out.size() - before) + " companions");
                }
            } catch (Exception e) {
                // One bad batch (timeout/502) shouldn't sink the rest.
                if (log != null) {
                    log.subqueryFailed(label, query, e.getMessage());
                }
            }
        }

        if (log != null) {
            log.message("Companion load " + companionProperty + "/" + roleQualifier
                    + " -> " + out.size() + " (subject,value,role) tuples\n");
        }
        return out;
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
