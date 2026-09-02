package wikidata.explore.query.result;

/** Bounded class-production result presented by the ordinary Instances viewer. */
public record ClassSampleResult(
        ObjectQueryResult instances,
        String requestedClass,
        String productionRoute,
        int requestedLimit,
        boolean truncated) {

    public ClassSampleResult {
        requestedClass = clean(requestedClass);
        productionRoute = clean(productionRoute);
        requestedLimit = Math.max(1, requestedLimit);
    }

    public int size() { return instances == null ? 0 : instances.size(); }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
