package wikidata.explore.extract;

public final class StringUtils {

    private StringUtils() {
    }

    /**
     * Returns the first non-null, non-blank string from the given values,
     * or null if none qualify.
     */
    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }
}
