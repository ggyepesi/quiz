package wikidata.explore.query.result;

import objectview.Viewable;

import java.util.List;

/**
 * @param typeOrder how the result's types relate, when its producer knows — sections are
 *                  shown in this order, and anything not named follows in the order it
 *                  was discovered. A sample of a derived class puts its production chain
 *                  here: without it the sections come out in traversal order, which
 *                  reads as no order at all.
 */
public record ObjectQueryResult(
        List<Viewable> objects,
        Class<?> primaryClass,
        String generatedSource,
        List<String> typeOrder) {

    public ObjectQueryResult {
        typeOrder = typeOrder == null ? List.of() : List.copyOf(typeOrder);
    }

    /** A result whose types have no stated order. */
    public ObjectQueryResult(
            List<Viewable> objects, Class<?> primaryClass, String generatedSource) {
        this(objects, primaryClass, generatedSource, List.of());
    }

    public int size() {
        return objects == null ? 0 : objects.size();
    }

    /** How many of these are instances of {@code typeName}. */
    public int countOf(String typeName) {
        if (objects == null || typeName == null || typeName.isBlank()) return size();
        return (int) objects.stream()
                .filter(object -> object != null && typeName.equals(object.typeName()))
                .count();
    }
}