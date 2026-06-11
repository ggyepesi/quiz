package wikidata.explore.query.core;

import java.util.Map;

public interface QueryDescriptor<R> {
    QueryKind kind();

    String purpose();

    String skeleton();

    Map<String, String> parameters();

    String requestText();

    int rowCount(R result);
}