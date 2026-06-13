package wikidata.explore.query.core;

import java.util.Map;

public interface Query<R> {
    String purpose();

    /**
     * Human-readable workflow description, not necessarily a concrete SPARQL/API template.
     */
    String skeleton();

    default String queryType() {
        return "Workflow";
    }

    Map<String, String> parameters();

    R execute(QueryContext context) throws Exception;

    int rowCount(R result);
}