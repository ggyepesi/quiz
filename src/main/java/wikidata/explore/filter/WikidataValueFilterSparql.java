package wikidata.explore.filter;

import java.util.Collection;

/**
 * Small SPARQL helper for numeric value filters.
 *
 * It assumes the entity variable is ?value, matching the current rule-tree
 * extraction queries.
 */
public final class WikidataValueFilterSparql {

    private WikidataValueFilterSparql() {
    }

    public static void appendSelectVariables(
            StringBuilder sb,
            Collection<WikidataValueFilter> filters) {

        if (filters == null) {
            return;
        }

        for (WikidataValueFilter f : filters) {
            if (!isValid(f)) {
                continue;
            }

            sb.append(" ?")
              .append(f.variableName());
        }
    }

    public static void appendWherePatterns(
            StringBuilder sb,
            Collection<WikidataValueFilter> filters) {

        if (filters == null) {
            return;
        }

        for (WikidataValueFilter f : filters) {
            if (!isValid(f)) {
                continue;
            }

            String var =
                    "?" + f.variableName();

            if (f.required()) {
                sb.append("  ?value wdt:")
                  .append(f.propertyPid())
                  .append(" ")
                  .append(var)
                  .append(" .\n");
            } else {
                sb.append("  OPTIONAL { ?value wdt:")
                  .append(f.propertyPid())
                  .append(" ")
                  .append(var)
                  .append(" . }\n");
            }

            sb.append("  FILTER(");

            if (f.required()) {
                sb.append("xsd:decimal(")
                  .append(var)
                  .append(") ");
            } else {
                sb.append("BOUND(")
                  .append(var)
                  .append(") && xsd:decimal(")
                  .append(var)
                  .append(") ");
            }

            sb.append(f.operator().sparql())
              .append(" ")
              .append(formatNumber(f.numericValue()))
              .append(")\n\n");
        }
    }

    private static boolean isValid(WikidataValueFilter f) {
        return f != null
                && f.propertyPid() != null
                && f.propertyPid().matches("P\\d+");
    }

    private static String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }

        if (d == Math.rint(d)) {
            return Long.toString((long) d);
        }

        return Double.toString(d);
    }
}
