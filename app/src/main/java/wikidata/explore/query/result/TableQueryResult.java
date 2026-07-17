package wikidata.explore.query.result;

import java.util.ArrayList;
import java.util.List;

public record TableQueryResult(
        List<String> columns,
        List<List<Object>> rows) {

    public TableQueryResult {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : deepCopy(rows);
    }

    public int size() {
        return rows.size();
    }

    private static List<List<Object>> deepCopy(List<List<Object>> in) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : in) {
            out.add(row == null ? List.of() : List.copyOf(row));
        }
        return List.copyOf(out);
    }
}