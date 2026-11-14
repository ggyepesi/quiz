package wikidata.explore.query.workflow;

import org.junit.jupiter.api.Test;
import work.LogNode;
import work.LogStatus;
import work.Query;
import work.QueryContext;
import work.QueryStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryWorkflowPartialTest {

    @Test void aPartialResultIsReturnedAndTheWorkflowNamesItAsPartial() throws Exception {
        List<LogNode> roots = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        Query<List<String>> query = new Query<>() {
            @Override public String purpose() { return "Inspect two requests"; }
            @Override public String skeleton() { return "two independent reads"; }
            @Override public Map<String, String> parameters() { return Map.of(); }
            @Override public List<String> execute(QueryContext context) {
                return List.of("preserved");
            }
            @Override public int rowCount(List<String> result) { return result.size(); }
            @Override public String summary(List<String> result) {
                return "1 result preserved; 1 request failed";
            }
            @Override public QueryStatus status(List<String> result) {
                return QueryStatus.PARTIAL;
            }
        };
        QueryWorkflow<List<String>> workflow = new QueryWorkflow<>(new QueryContext(),
                accepted::addAll, (root, added, terminal) -> {
                    if (added && !roots.contains(root)) roots.add(root);
                });

        assertEquals(List.of("preserved"), workflow.run(query));
        assertEquals(List.of("preserved"), accepted);
        assertEquals(LogStatus.PARTIAL, roots.getFirst().status());
        assertEquals("1 result preserved; 1 request failed", roots.getFirst().summary());
    }
}
