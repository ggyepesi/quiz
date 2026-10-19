package wikidata.explore.query.template.rule;

import wikidata.explore.rule.RuleIncludedField;
import java.util.List;

public final class RuleIncludedFieldSparql {

    private RuleIncludedFieldSparql() {
    }

    public static String variableName(
            RuleIncludedField field,
            int index) {

        String base = field == null
                || field.fieldName() == null
                || field.fieldName().isBlank()
                ? "field"
                : field.fieldName();

        base = base.replaceAll("[^A-Za-z0-9_]", "_")
                   .replaceAll("_+", "_");

        if (base.startsWith("_")) {
            base = base.substring(1);
        }

        if (base.endsWith("_")) {
            base = base.substring(0, base.length() - 1);
        }

        if (base.isBlank()) {
            base = "field";
        }

        if (Character.isDigit(base.charAt(0))) {
            base = "v_" + base;
        }

        return base + "_" + Math.max(0, index);
    }

    public static void appendSelectVariables(
            StringBuilder sb,
            List<RuleIncludedField> fields) {

        if (fields == null || fields.isEmpty()) {
            return;
        }

        int index = 0;

        for (RuleIncludedField field : fields) {
            if (field == null) {
                index++;
                continue;
            }

            String var = variableName(field, index);

            sb.append(" ?").append(var);

            if (!field.isMediaField()) {
                sb.append(" ?").append(var).append("Label");
            }

            index++;
        }
    }


    /**
     * The statement value node for a time-valued field, packed into ONE bound string.
     *
     * <p>{@code wdt:} yields the bare timestamp, dropping the calendar the value was
     * stated in and the precision it was stated at — neither recoverable from the
     * numbers, since Wikidata records 1047 as Gregorian and 1576 as Julian, and pads
     * a year to {@code -01-01} exactly as a real 1 January is written.
     *
     * <p>The three are concatenated rather than bound separately because an
     * aggregating caller's {@code SAMPLE} picks each variable independently, which on
     * a field with several statements would pair one statement's time with another's
     * calendar. One packed string can only be taken whole. The form is the one
     * {@link wikidata.CalendarModelCodec} builds for the API too, so both loading
     * paths share one wire form and one translator.
     *
     * @param bindTo the variable the packed value is bound to — the field's own
     *               variable on the direct path, its {@code _s} source on the
     *               aggregating one.
     */
    public static String datePattern(RuleIncludedField field, String var, String bindTo) {
        String pid = wikidata.explore.rule.RuleNode.cleanPid(field.propertyPid());
        return wikidata.query.StatementPatterns.statement("?value", pid,
                        "?" + var + "_statement", field.qualifierCondition()) + "\n"
             + "?" + var + "_statement psv:" + pid + " ?" + var + "_n .\n"
             + "?" + var + "_n wikibase:timeValue ?" + var + "_t ;"
             + " wikibase:timePrecision ?" + var + "_p ;"
             + " wikibase:timeCalendarModel ?" + var + "_c .\n"
             + "BIND(CONCAT(STR(?" + var + "_t), "
             + wikidata.CalendarModelCodec.calendarMarkExpression(var + "_c") + ", "
             + "\" [precision=\", STR(?" + var + "_p), \"]\") AS ?" + bindTo + ")\n";
    }

    /**
     * Binds {@code object} to the field's value on {@code entity}, in the field's
     * direction, reading only the statements its qualifier condition admits. THE one
     * spelling of "this field's value" for every query that reads one, so a filter,
     * a ranking and a stored value cannot disagree about which statements count.
     */
    public static String valuePattern(
            RuleIncludedField field, String entity, String object) {
        return valuePattern(field.propertyPid(), field.direction(),
                field.qualifierCondition(), entity, object);
    }

    public static String valuePattern(String propertyPid,
            wikidata.explore.model.RuleDirection direction,
            wikidata.QualifierCondition condition, String entity, String object) {
        String pid = wikidata.explore.rule.RuleNode.cleanPid(propertyPid);
        return direction == wikidata.explore.model.RuleDirection.ITEM_TO_ROOT
                ? wikidata.query.StatementPatterns.value(object, pid, entity, condition, object)
                : wikidata.query.StatementPatterns.value(entity, pid, object, condition, object);
    }

    /**
     * The referenced class's membership applied to one field value. This is the same
     * rule whether the field is loaded inline, in a member batch, or while sampling.
     */
    public static String membershipPattern(
            RuleIncludedField field, String value, String name) {
        if (field == null || !field.hasMembership()) return "";
        String pid = wikidata.explore.rule.RuleNode.cleanPid(field.membershipPid());
        wikidata.QualifierCondition condition = field.membershipQualifierCondition();
        if (condition != null && condition.complete()) {
            String statement = "?" + name.replace("?", "")
                    .replaceAll("[^A-Za-z0-9_]", "_") + "_membershipStatement";
            boolean capturesMainValue = !field.membershipQid().isBlank();
            String pattern = capturesMainValue
                    ? wikidata.query.StatementPatterns.qualifiedMembership(
                            value, pid, statement, condition)
                    : wikidata.query.StatementPatterns.qualifiedMembershipExists(
                            value, pid, statement, condition);
            if (capturesMainValue) {
                pattern += "\n" + statement + " ps:" + pid
                        + " wd:" + field.membershipQid() + " .";
            }
            return pattern;
        }
        return value + " wdt:" + pid + " wd:" + field.membershipQid() + " .";
    }

    /** Typed time expression recovered from the packed field value itself. */
    public static String packedTimeExpression(String packedVar) {
        return "xsd:dateTime(STRBEFORE(?" + packedVar + ", \"|\"))";
    }

    /** Whether this field's value has to be read from the statement's value node. */
    public static boolean readsValueNode(RuleIncludedField field) {
        return field != null
                && field.kind() == RuleIncludedField.FieldKind.DATE
                && field.propertyPid() != null
                && !field.propertyPid().isBlank();
    }

    public static void appendWherePatterns(
            StringBuilder sb,
            List<RuleIncludedField> fields) {
        appendWherePatterns(sb, fields, true);
    }

    /** {@code withLabels=false} omits the inline {@code rdfs:label} blocks —
     *  use it when the query labels via {@code SERVICE wikibase:label} instead
     *  (the service would otherwise clash with an already-bound ?xLabel). */
    public static void appendWherePatterns(
            StringBuilder sb,
            List<RuleIncludedField> fields,
            boolean withLabels) {
        appendWherePatterns(sb, fields, withLabels, java.util.Set.of());
    }

    /**
     * Emits the WHERE patterns for {@code fields}, skipping those in
     * {@code skip} but STILL advancing the index — so the field variable
     * ({@code variableName(field, i)}) matches the SELECT and the extractor,
     * which index over the full list with skip-and-increment. Pass the FULL
     * field list (not a pre-filtered one), or a skipped field shifts the indices
     * of later fields and their pattern var no longer matches the select var
     * (e.g. image selected as ?image_1 but bound as ?image_0).
     */
    public static void appendWherePatterns(
            StringBuilder sb,
            List<RuleIncludedField> fields,
            boolean withLabels,
            java.util.Collection<RuleIncludedField> skip) {

        if (fields == null || fields.isEmpty()) {
            return;
        }

        int index = 0;

        for (RuleIncludedField field : fields) {
            if (field == null
                    || field.propertyPid() == null
                    || field.propertyPid().isBlank()) {
                index++;
                continue;
            }

            if (skip != null && skip.contains(field)) {
                index++;
                continue;
            }

            String var = variableName(field, index);
            boolean label = withLabels && !field.isMediaField();
            // ROOT_TO_ITEM: ?value wdt:P ?var ; ITEM_TO_ROOT: ?var wdt:P ?value.
            // A date is always outgoing (the compiler forces it), and is read from
            // the statement's value node so its calendar and precision survive.
            String triple = readsValueNode(field)
                    ? datePattern(field, var, var)
                    : valuePattern(field, "?value", "?" + var);
            // Constrain the value to the referenced class's type, if requested.
            String typeConstraint = membershipPattern(field, "?" + var, var);
            if (!typeConstraint.isEmpty()) typeConstraint += "\n";

            if (field.optional()) {
                sb.append("  OPTIONAL {\n")
                  .append("    ").append(triple).append("\n");
                if (!typeConstraint.isEmpty()) {
                    sb.append("    ").append(typeConstraint);
                }
                if (label) {
                    sb.append("    OPTIONAL {\n")
                      .append("      ?")
                      .append(var)
                      .append(" rdfs:label ?")
                      .append(var)
                      .append("Label .\n")
                      .append("      FILTER(LANG(?")
                      .append(var)
                      .append("Label) = \"en\")\n")
                      .append("    }\n");
                }
                sb.append("  }\n");
            } else {
                sb.append("  ").append(triple).append("\n");
                if (!typeConstraint.isEmpty()) {
                    sb.append("  ").append(typeConstraint);
                }
                if (label) {
                    sb.append("  OPTIONAL {\n")
                      .append("    ?")
                      .append(var)
                      .append(" rdfs:label ?")
                      .append(var)
                      .append("Label .\n")
                      .append("    FILTER(LANG(?")
                      .append(var)
                      .append("Label) = \"en\")\n")
                      .append("  }\n");
                }
            }

            index++;
        }
    }
}
