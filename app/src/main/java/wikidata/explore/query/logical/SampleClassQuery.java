package wikidata.explore.query.logical;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.query.template.rule.RuleTreeQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

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
        return p;
    }

    @Override
    public TableQueryResult execute(QueryContext context) throws Exception {
        RuleNode sample =
                node.sampleCopy(limit);

        String sparql =
                RuleTreeQueries.valuesQueryWithoutIncludedFields(sample);

        Map<String, String> stepParams = new LinkedHashMap<>();
        stepParams.put("classQid", node == null ? "" : node.sourceQid());
        stepParams.put("limit", String.valueOf(limit));

        return context.step(
                "Sample rows via SPARQL",
                "SPARQL",
                null,
                stepParams,
                step -> {
                    step.request(sparql);

                    List<List<Object>> rows = new ArrayList<>();

                    for (WikidataBinding b : WikidataAccess.sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String qid = b.qid("value");
                        String label = b.label("value");

                        if (qid != null && WikidataIds.isQid(qid)) {
                            rows.add(List.of(
                                    qid,
                                    label == null ? "" : label,
                                    "",
                                    ""));
                        }
                    }

                    step.summary(rows.size() + " sampled");

                    return new TableQueryResult(
                            List.of("QID", node.name(), "", ""),
                            rows);
                });
    }

    @Override
    public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(TableQueryResult result) {
        return rowCount(result) + " samples";
    }
}