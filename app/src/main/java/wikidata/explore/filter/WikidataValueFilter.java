package wikidata.explore.filter;

/**
 * First version: numeric filter on a direct Wikidata property.
 *
 * Example:
 *
 *   fieldName      apparentMagnitude
 *   propertyPid    P1215
 *   operator       LE
 *   numericValue   6.0
 *
 * Emits:
 *
 *   OPTIONAL { ?value wdt:P1215 ?apparentMagnitude . }
 *   FILTER(BOUND(?apparentMagnitude)
 *          && xsd:decimal(?apparentMagnitude) <= 6.0)
 */
public class WikidataValueFilter {

    private String fieldName;
    private String propertyPid;
    private String propertyLabel;
    private WikidataValueFilterOperator operator =
            WikidataValueFilterOperator.LE;
    private double numericValue;
    private boolean required = true;

    public WikidataValueFilter() {
    }

    public WikidataValueFilter(
            String fieldName,
            String propertyPid,
            String propertyLabel,
            WikidataValueFilterOperator operator,
            double numericValue,
            boolean required) {

        this.fieldName = fieldName;
        this.propertyPid = cleanPid(propertyPid);
        this.propertyLabel = propertyLabel;
        this.operator = operator == null
                ? WikidataValueFilterOperator.LE
                : operator;
        this.numericValue = numericValue;
        this.required = required;
    }

    public String fieldName() {
        return fieldName;
    }

    public void fieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public String propertyPid() {
        return propertyPid;
    }

    public void propertyPid(String propertyPid) {
        this.propertyPid = cleanPid(propertyPid);
    }

    public String propertyLabel() {
        return propertyLabel;
    }

    public void propertyLabel(String propertyLabel) {
        this.propertyLabel = propertyLabel;
    }

    public WikidataValueFilterOperator operator() {
        return operator;
    }

    public void operator(WikidataValueFilterOperator operator) {
        this.operator = operator == null
                ? WikidataValueFilterOperator.LE
                : operator;
    }

    public double numericValue() {
        return numericValue;
    }

    public void numericValue(double numericValue) {
        this.numericValue = numericValue;
    }

    public boolean required() {
        return required;
    }

    public void required(boolean required) {
        this.required = required;
    }

    public String variableName() {
        String base = fieldName;

        if (base == null || base.isBlank()) {
            base = propertyPid;
        }

        if (base == null || base.isBlank()) {
            base = "filterValue";
        }

        base = base.trim()
                   .replaceAll("[^A-Za-z0-9_]+", "_")
                   .replaceAll("_+", "_");

        if (base.startsWith("_")) {
            base = base.substring(1);
        }

        if (base.endsWith("_")) {
            base = base.substring(0, base.length() - 1);
        }

        if (base.isBlank()) {
            base = "filterValue";
        }

        if (Character.isDigit(base.charAt(0))) {
            base = "v_" + base;
        }

        return Character.toLowerCase(base.charAt(0)) + base.substring(1);
    }

    public String displayName() {
        String label = propertyLabel == null || propertyLabel.isBlank()
                ? propertyPid
                : propertyLabel;

        return label
                + " "
                + operator
                + " "
                + numericValue;
    }

    @Override
    public String toString() {
        return displayName();
    }

    public static String cleanPid(String pid) {
        if (pid == null) {
            return "";
        }

        pid = pid.trim();

        if (pid.startsWith("wdt:")) {
            pid = pid.substring(4);
        }

        return pid.trim();
    }
}
