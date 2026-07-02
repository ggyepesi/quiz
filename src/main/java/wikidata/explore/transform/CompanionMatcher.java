package wikidata.explore.transform;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Derives a boolean outcome on a reified record by matching it against a
 * <b>companion statement</b> on the same subject — a general pattern, not
 * award-specific.
 *
 * <p>A reified record (e.g. a nomination) is one statement of the subject:
 * {@code subject P_a [value, role-qualifier=role]}. A companion statement
 * {@code subject P_b [value, other-role-qualifier=role]} on the same subject
 * signals a positive outcome. The record's outcome is true iff a companion exists
 * with the <i>same</i> {@code (subject, value, role)}.
 *
 * <p>Oscars are the motivating case: nominated = {@code P1411[category, P2453=nominee]},
 * won = {@code P166[category, P1346=winner]}; a nomination won iff the film has a
 * P166 for the same category whose winner is the nominee. But the same shape covers
 * any award (Grammy, Emmy, Nobel) or any "proposed vs. accepted"-style companion.
 *
 * <p>This is the pure match over a precomputed companion-set; the SPARQL load that
 * produces the set (each subject's {@code P_b}/role) is separate and configured by
 * the two companion PIDs.
 */
public final class CompanionMatcher {

    private CompanionMatcher() {}

    /**
     * Sets {@code outcomeField} to true/false on every record.
     *
     * @param companions companion keys as {@code [subjectQid, valueQid, roleQid]}
     * @return how many records matched (outcome = true)
     */
    public static int apply(Collection<WikidataDynamicObject> records,
                            Set<List<String>> companions,
                            String subjectField,
                            String valueField,
                            String roleField,
                            String outcomeField,
                            GenerationLog log) {

        if (records == null || companions == null) {
            return 0;
        }

        int matched = 0;
        for (WikidataDynamicObject r : records) {
            List<String> key = key(r, subjectField, valueField, roleField);
            boolean outcome = key != null && companions.contains(key);
            if (r != null) {
                r.put(outcomeField, outcome);
            }
            if (outcome) {
                matched++;
            }
        }

        if (log != null) {
            log.message("Companion match '" + outcomeField + "': " + matched
                    + " of " + records.size() + "\n");
        }
        return matched;
    }

    /** The companion key for a record, or null if any part is missing. */
    public static List<String> key(WikidataDynamicObject r,
                                   String subjectField,
                                   String valueField,
                                   String roleField) {
        if (r == null) {
            return null;
        }
        String subject = qid(r.get(subjectField));
        String value = qid(r.get(valueField));
        String role = qid(r.get(roleField));
        return subject.isEmpty() || value.isEmpty() || role.isEmpty()
                ? null
                : List.of(subject, value, role);
    }

    private static String qid(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return w.qid() == null ? "" : w.qid();
        }
        return v == null ? "" : String.valueOf(v);
    }
}
