package wikidata.explore.compiled;

import wikidata.explore.model.GeneratedFacet;

/**
 * Immutable runtime form of a declared grouping facet.
 */
public record CompiledFacet(
        String name,
        String fieldName,
        GeneratedFacet.Bucketing bucketing,
        int rangeSize) {

    public CompiledFacet {
        name = clean(name);
        fieldName = clean(fieldName);
        bucketing = bucketing == null
                ? GeneratedFacet.Bucketing.VALUE
                : bucketing;
        rangeSize = rangeSize <= 0 ? 10 : rangeSize;
    }

    public static CompiledFacet from(GeneratedFacet facet) {
        GeneratedFacet source =
                facet == null ? new GeneratedFacet() : facet;
        return new CompiledFacet(
                source.name(),
                source.fieldName(),
                source.bucketing(),
                source.rangeSize());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
