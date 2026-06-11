package wikidata.explore.query.result;

import quiz.Quizable;

import java.util.List;

public record ObjectQueryResult(
        List<Quizable> objects,
        Class<?> primaryClass,
        String generatedSource) {

    public int size() {
        return objects == null ? 0 : objects.size();
    }
}