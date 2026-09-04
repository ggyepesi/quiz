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

    /**
     * How many instances OF THE SAMPLED CLASS this holds.
     *
     * <p>Not how many rows: a derived class's sample also carries the population it was
     * produced from, and counting those made the window title say sixteen while the
     * section beside it said eight. The count a reader is asking for is of the class
     * they sampled.
     */
    public int size() {
        return instances == null ? 0 : instances.countOf(requestedClass);
    }

    /** Every row, including the production chain behind the sampled class. */
    public int rowCount() { return instances == null ? 0 : instances.size(); }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
