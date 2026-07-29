package wikidata.explore.query.result;

import objectview.Viewable;

import java.util.List;

public record ObjectQueryResult(
        List<Viewable> objects,
        Class<?> primaryClass,
        String generatedSource) {

    public int size() {
        return objects == null ? 0 : objects.size();
    }
}