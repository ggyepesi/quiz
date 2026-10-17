package wikidata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Which of a property's statements a field reads: only those carrying qualifier
 * {@code qualifierPid} with one of {@code allowedQids} — e.g. an apparent magnitude
 * (P1215) stated in the V band (P1227 = Q4892529), where Wikidata keeps one statement
 * per photometric band and an unranked star otherwise answers with every band.
 *
 * <p>The meaning is fixed here, once, for every reader (the SPARQL patterns and the
 * wbgetentities claims alike): deprecated statements never count; of the statements
 * that match, the preferred ones are read when any match, otherwise the normal ones.
 * That is the truthy rule {@code wdt:} applies, taken AFTER the condition — so a star
 * whose preferred statement is in another band still reads its V-band value, and a
 * star with no V-band statement has no value at all.
 */
public record QualifierCondition(String qualifierPid, List<String> allowedQids) {

    public QualifierCondition {
        qualifierPid = qualifierPid == null ? "" : qualifierPid.trim();
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (allowedQids != null) {
            for (String qid : allowedQids) {
                if (qid != null && !qid.isBlank()) clean.add(qid.trim());
            }
        }
        allowedQids = List.copyOf(clean);
    }

    /** Null when nothing is configured, so an absent condition stays absent. */
    public static QualifierCondition of(String qualifierPid, List<String> allowedQids) {
        QualifierCondition condition = new QualifierCondition(qualifierPid, allowedQids);
        return condition.qualifierPid().isBlank() && condition.allowedQids().isEmpty()
                ? null : condition;
    }

    /** A qualifier property and at least one allowed value, all well formed. */
    public boolean complete() {
        return WikidataIds.isPid(qualifierPid) && !allowedQids.isEmpty()
                && allowedQids.stream().allMatch(WikidataIds::isQid);
    }

    /** Whether one statement's qualifiers satisfy the condition. */
    public boolean matches(Map<String, List<String>> qualifiers) {
        if (qualifiers == null) return false;
        for (String value : qualifiers.getOrDefault(qualifierPid, List.of())) {
            if (allowedQids.contains(value)) return true;
        }
        return false;
    }

    /**
     * The statements a field reads, under {@code condition} (null = every statement),
     * with the truthy rule applied after it: never deprecated, and the preferred ones
     * when any qualify.
     */
    public static <T> List<T> select(List<T> statements, QualifierCondition condition,
            Function<T, String> rank, Function<T, Map<String, List<String>>> qualifiers) {
        if (statements == null || statements.isEmpty()) return List.of();
        List<T> preferred = new ArrayList<>();
        List<T> normal = new ArrayList<>();
        for (T statement : statements) {
            String r = rank.apply(statement);
            if ("deprecated".equals(r)) continue;
            if (condition != null && !condition.matches(qualifiers.apply(statement))) continue;
            if ("preferred".equals(r)) preferred.add(statement);
            else normal.add(statement);
        }
        return List.copyOf(preferred.isEmpty() ? normal : preferred);
    }

    /** "P1227 is one of Q4892529" — for logs, explanations and validation messages. */
    public String display() {
        return qualifierPid + " is one of " + String.join(", ", allowedQids);
    }

    @Override
    public String toString() {
        return display();
    }
}
