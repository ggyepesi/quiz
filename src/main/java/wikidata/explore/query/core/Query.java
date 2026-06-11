package wikidata.explore.query.core;

import java.util.Map;

public interface Query<R> {
    String purpose();

    String skeleton();

    Map<String, String> parameters();

    R execute(QueryContext context) throws Exception;

    int rowCount(R result);
}