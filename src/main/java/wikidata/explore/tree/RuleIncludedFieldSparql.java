package wikidata.explore.tree;

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

            String var = variableName(field, index);

            if (field.optional()) {
                sb.append("  OPTIONAL {\n")
                  .append("    ?value wdt:")
                  .append(field.propertyPid())
                  .append(" ?")
                  .append(var)
                  .append(" .\n");
                if (!field.isMediaField()) {
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
                if (!field.isMediaField()) {
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
