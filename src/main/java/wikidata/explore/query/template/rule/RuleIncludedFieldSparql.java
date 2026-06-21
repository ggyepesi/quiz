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

            if (field.optional()) {
                sb.append("  OPTIONAL {\n")
                  .append("    ?value wdt:")
                  .append(field.propertyPid())
                  .append(" ?")
                  .append(var)
                  .append(" .\n");
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
                sb.append("  ?value wdt:")
                  .append(field.propertyPid())
                  .append(" ?")
                  .append(var)
                  .append(" .\n");
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
