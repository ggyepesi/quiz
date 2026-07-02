package wikidata.explore.transform;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.GenerationLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the companion-set for a {@link CompanionMatcher}: for each subject, the
 * statements {@code subject companionProperty [ps=value, pq:roleQualifier=role]},
 * as {@code (subjectQid, valueQid, roleQid)} tuples.
 *
 * <p>Oscars: {@code film P166 [ps=category, pq:P1346=winner]} — the wins. Generic:
 * the two PIDs are the only inputs. Subject-anchored, batched VALUES (WDQS-friendly).
 */
public final class CompanionLoader {

    private static final int BATCH = 200;

    private CompanionLoader() {}

    public static Set<List<String>> load(
            Collection<String> subjectQids,
            String companionProperty,
            String roleQualifier,
            WikidataSparqlClient client,
            GenerationLog log) {

        Set<List<String>> out = new HashSet<>();
        if (subjectQids == null || client == null
                || companionProperty == null || !companionProperty.matches("(?i)P\\d+")
                || roleQualifier == null || !roleQualifier.matches("(?i)P\\d+")) {
            return out;
        }

        List<String> subjects = new ArrayList<>(new java.util.LinkedHashSet<>(
                subjectQids.stream().filter(q -> q != null && q.matches("Q\\d+")).toList()));
        if (subjects.isEmpty()) {
            return out;
        }

        int total = (subjects.size() + BATCH - 1) / BATCH;
        int idx = 0;
        for (int from = 0; from < subjects.size(); from += BATCH) {
            if (Thread.currentThread().isInterrupted()) {
                if (log != null) {
                    log.message("Companion load cancelled.\n");
                }
                break;
            }
            List<String> batch = subjects.subList(
                    from, Math.min(from + BATCH, subjects.size()));
            String query = buildQuery(batch, companionProperty, roleQualifier);
            int before = out.size();
            String label = "Companion load " + (++idx) + "/" + total + " ("
                    + companionProperty + "/" + roleQualifier + ", " + batch.size()
                    + " subjects)";
            try {
                for (WikidataBinding row : client.query(query)) {
                    String subj = row.qid("subj");
                    String value = row.qid("value");
                    String role = row.qid("role");
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

    // subject companionProperty [ps=value, pq:roleQualifier=role]
    static String buildQuery(List<String> subjects, String prop, String roleQual) {
        StringBuilder values = new StringBuilder();
        for (String q : subjects) {
            values.append("wd:").append(q).append(" ");
        }
        return "SELECT ?subj ?value ?role WHERE {\n"
                + "  VALUES ?subj { " + values.toString().trim() + " }\n"
                + "  ?subj p:" + prop + " ?st .\n"
                + "  ?st ps:" + prop + " ?value .\n"
                + "  ?st pq:" + roleQual + " ?role .\n"
                + "}\n";
    }
}
