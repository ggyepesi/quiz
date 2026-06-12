package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.tree.NodeSamplePanel;
import wikidata.explore.tree.RuleNode;
import wikidata.explore.tree.RuleTreeQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SampleClassQuery implements Query<TableQueryResult> {

    private final RuleNode node;
    private final int limit;

    public SampleClassQuery(RuleNode node, int limit) {
        this.node = node;
        this.limit = Math.max(1, limit);
    }

    @Override
    public String purpose() {
        return "Sample class instances";
    }

    @Override
    public String skeleton() {
        return "RuleNode -> valuesQueryWithoutIncludedFields -> sample class rows";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("class", node == null ? "" : node.name());
        p.put("classQid", node == null ? "" : node.sourceQid());
        p.put("limit", String.valueOf(limit));
        return p;
    }

    @Override
    public TableQueryResult execute(QueryContext context) throws Exception {
        RuleNode sample =
                NodeSamplePanel.sampleNode(node, limit);

        String sparql =
                RuleTreeQueries.valuesQueryWithoutIncludedFields(sample);

        context.logText("\nSAMPLE class instances\n----------------------");
        context.logText(sparql);

        List<List<Object>> rows = new ArrayList<>();

        for (WikidataBinding b : context.sparql().query(sparql)) {
            String qid = b.qid("value");
            String label = b.label("value");

            if (qid != null && qid.matches("Q\\d+")) {
                rows.add(List.of(qid, label == null ? "" : label, "", ""));
            }
        }

        return new TableQueryResult(
                List.of("QID", node.name(), "", ""),
                rows);
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }
}