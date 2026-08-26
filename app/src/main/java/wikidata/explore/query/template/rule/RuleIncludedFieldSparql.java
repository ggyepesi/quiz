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
    public static String datePattern(String pid, String var, String bindTo) {
        return "?value p:" + pid + " ?" + var + "_statement .\n"
             + "?" + var + "_statement a wikibase:BestRank ;"
             + " psv:" + pid + " ?" + var + "_n .\n"
             + "?" + var + "_n wikibase:timeValue ?" + var + "_t ;"
             + " wikibase:timePrecision ?" + var + "_p ;"
             + " wikibase:timeCalendarModel ?" + var + "_c .\n"
             + "BIND(CONCAT(STR(?" + var + "_t), "
             + wikidata.CalendarModelCodec.calendarMarkExpression(var + "_c") + ", "
             + "\" [precision=\", STR(?" + var + "_p), \"]\") AS ?" + bindTo + ")\n";
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
                    ? datePattern(
                            wikidata.explore.rule.RuleNode.cleanPid(field.propertyPid()),
                            var, var)
                    : field.direction()
                            .triplePattern("?value", "?" + var, field.propertyPid());
            // Constrain the value to the referenced class's type, if requested.
            String typeConstraint = field.hasMembership()
                    ? "?" + var + " wdt:" + field.membershipPid()
                            + " wd:" + field.membershipQid() + " .\n"
                    : "";

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
