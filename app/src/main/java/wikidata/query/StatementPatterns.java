package wikidata.query;

import wikidata.QualifierCondition;

/**
 * The SPARQL for reading a property's value, with or without a
 * {@link QualifierCondition}. Without one it is the truthy {@code wdt:} triple every
 * query has always used. With one, the value is read through the statement node, so
 * the qualifier can be tested and the truthy rule applied after it — exactly what
 * {@link QualifierCondition#select} does to wbgetentities claims, so the query that
 * filters and ranks an entity and the load that stores its value read the same
 * statements.
 */
public final class StatementPatterns {

    private StatementPatterns() { }

    /**
     * Binds {@code object} to {@code subject}'s {@code pid} values. Both terms are
     * written as given ({@code ?value}, {@code wd:Q1}); {@code name} (the field's own
     * variable) names the helper variables, so two patterns in one query never share
     * them.
     */
    public static String value(String subject, String pid, String object,
            QualifierCondition condition, String name) {
        if (!usable(condition)) {
            return subject + " wdt:" + pid + " " + object + " .";
        }
        String statement = "?" + name.replace("?", "").replaceAll("[^A-Za-z0-9_]", "_")
                + "_qs";
        return statement(subject, pid, statement, condition)
                + "\n" + statement + " ps:" + pid + " " + object + " .";
    }

    /**
     * Selects {@code statementVar} among {@code subject}'s {@code pid} statements:
     * the best-ranked ones without a condition, or the best-ranked of those the
     * condition admits. The caller reads the value from it ({@code ps:}/{@code psv:}).
     */
    public static String statement(String subject, String pid, String statementVar,
            QualifierCondition condition) {
        if (!usable(condition)) {
            return subject + " p:" + pid + " " + statementVar + " .\n"
                    + statementVar + " a wikibase:BestRank .";
        }
        String base = statementVar.replace("?", "");
        String value = "?" + base + "_qv";
        String rank = "?" + base + "_qr";
        String other = "?" + base + "_qo";
        String otherValue = "?" + base + "_qov";
        String qualifier = "pq:" + condition.qualifierPid();
        return subject + " p:" + pid + " " + statementVar + " .\n"
                + allowedValues(value, condition) + "\n"
                + statementVar + " " + qualifier + " " + value
                + " ; wikibase:rank " + rank + " .\n"
                + "FILTER(" + rank + " != wikibase:DeprecatedRank)\n"
                // A preferred statement outside the condition must not hide a normal
                // one inside it; a preferred one inside it does.
                + "FILTER(" + rank + " = wikibase:PreferredRank || NOT EXISTS { "
                + subject + " p:" + pid + " " + other + " . "
                + allowedValues(otherValue, condition) + " "
                + other + " " + qualifier + " " + otherValue
                + " ; wikibase:rank wikibase:PreferredRank . "
                + "})";
    }

    /**
     * Selects entities by a qualified statement rather than by its main value. The
     * qualifier is written first deliberately: for a selective catalogue marker this
     * lets Blazegraph start from that small index before joining the owning entity.
     * The rank rule is identical to {@link #statement}; only the join order differs.
     */
    public static String qualifiedMembership(String subject, String pid,
            String statementVar, QualifierCondition condition) {
        if (!usable(condition)) {
            throw new IllegalArgumentException(
                    "Qualified statement membership needs a complete qualifier condition");
        }
        String base = statementVar.replace("?", "");
        String value = "?" + base + "_qv";
        String rank = "?" + base + "_qr";
        String other = "?" + base + "_qo";
        String otherValue = "?" + base + "_qov";
        String qualifier = "pq:" + condition.qualifierPid();
        return allowedValues(value, condition) + "\n"
                + statementVar + " " + qualifier + " " + value
                + " ; wikibase:rank " + rank + " .\n"
                + "FILTER(" + rank + " != wikibase:DeprecatedRank)\n"
                + subject + " p:" + pid + " " + statementVar + " .\n"
                + "FILTER(" + rank + " = wikibase:PreferredRank || NOT EXISTS { "
                + subject + " p:" + pid + " " + other + " . "
                + allowedValues(otherValue, condition) + " "
                + other + " " + qualifier + " " + otherValue
                + " ; wikibase:rank wikibase:PreferredRank . "
                + "})";
    }

    /**
     * Admits an entity when at least one non-deprecated {@code pid} statement carries
     * the configured qualifier. Unlike {@link #qualifiedMembership}, this deliberately
     * does not choose between normal and preferred matching statements: membership is
     * boolean, so either rank admits the same entity. Avoiding that irrelevant choice
     * also avoids a correlated {@code NOT EXISTS} for every candidate.
     */
    public static String qualifiedMembershipExists(String subject, String pid,
            String statementVar, QualifierCondition condition) {
        if (!usable(condition)) {
            throw new IllegalArgumentException(
                    "Qualified statement membership needs a complete qualifier condition");
        }
        String base = statementVar.replace("?", "");
        String value = "?" + base + "_qv";
        String rank = "?" + base + "_qr";
        return allowedValues(value, condition) + "\n"
                + statementVar + " pq:" + condition.qualifierPid() + " " + value
                + " ; wikibase:rank " + rank + " .\n"
                + "FILTER(" + rank + " != wikibase:DeprecatedRank)\n"
                + subject + " p:" + pid + " " + statementVar + " .";
    }

    private static boolean usable(QualifierCondition condition) {
        return condition != null && condition.complete();
    }

    private static String allowedValues(String variable, QualifierCondition condition) {
        return "VALUES " + variable + " { " + String.join(" ",
                condition.allowedQids().stream().map(qid -> "wd:" + qid).toList()) + " }";
    }
}
