package wikidata.explore.query.core;

import java.util.Map;

public interface Query<R> {
    String purpose();

    /**
     * Query skeleton/template, not workflow description.
     */
    String skeleton();

    default String queryType() {
        return "Workflow";
    }

    default String description() {
        return "";
    }

    Map<String, String> parameters();

    R execute(QueryContext context) throws Exception;

    int rowCount(R result);

    /**
     * Workflow-level result summary shown on the workflow log. Override to
     * use a domain-specific unit ("92 objects", "39 possibilities"); the
     * default counts rows.
     */
    default String summary(R result) {
        return rowCount(result) + " rows";
    }
}