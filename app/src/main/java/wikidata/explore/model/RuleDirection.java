package wikidata.explore.model;

public enum RuleDirection {
    ROOT_TO_ITEM,
    ITEM_TO_ROOT;

    public String triplePattern(
            String rootVar,
            String itemVar,
            String propertyPid) {

        return switch (this) {
            case ROOT_TO_ITEM ->
                    rootVar + " wdt:" + propertyPid + " " + itemVar + " .";

            case ITEM_TO_ROOT ->
                    itemVar + " wdt:" + propertyPid + " " + rootVar + " .";
        };
    }

    public String previewText(
            String itemName,
            String propertyLabel,
            String rootLabel) {

        String item =
                blankTo(itemName, "item");

        String property =
                blankTo(propertyLabel, "property");

        String root =
                blankTo(rootLabel, "root");

        return switch (this) {
            case ROOT_TO_ITEM ->
                    root + " → " + property + " → " + item;

            case ITEM_TO_ROOT ->
                    item + " → " + property + " → " + root;
        };
    }

    public String exampleText(String propertyPid) {
        String pid =
                blankTo(propertyPid, "Pxx");

        return switch (this) {
            case ROOT_TO_ITEM ->
                    "?root wdt:" + pid + " ?value";

            case ITEM_TO_ROOT ->
                    "?value wdt:" + pid + " ?root";
        };
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank()
                ? fallback
                : s.trim();
    }

    @Override
    public String toString() {
        return switch (this) {
            case ROOT_TO_ITEM -> "root → item";
            case ITEM_TO_ROOT -> "item → root";
        };
    }
}
