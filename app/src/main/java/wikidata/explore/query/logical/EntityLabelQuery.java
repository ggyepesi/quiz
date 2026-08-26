package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Datasource;
import work.Query;
import work.QueryContext;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.LinkedHashMap;
import java.util.Map;
import wikidata.explore.query.core.WikidataAccess;

/**
 * Resolves the English label of one entity. Cheap side lookup — run it
 * via SwingQueryRunner#runQuiet so it does not toggle busy state.
 */
public class EntityLabelQuery implements Query<String> {

    private final String qid;

    public EntityLabelQuery(String qid) {
        this.qid = qid == null ? "" : qid.trim();
    }

    @Override
    public String purpose() {
        return "Resolve entity label";
    }

    @Override
    public String skeleton() {
        return "wd:<qid> rdfs:label -> label";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("qid", qid);
        return p;
    }

    @Override
    public String execute(QueryContext context) throws Exception {
        return context.step(
                purpose(), queryType(), skeleton(), parameters(),
                step -> {
                    String sparql = SparqlQueries.entityLabel(qid, "en");
                    step.request(sparql);

                    for (WikidataBinding b : WikidataAccess
                            .sparql(context, Datasource.WIKIDATA).query(sparql)) {
                        String label = b.value("label");
                        step.summary(label == null ? "no label" : label);
                        return label;
                    }
                    step.summary("no label");
                    return null;
                });
    }

    @Override
    public int rowCount(String result) {
        return result == null ? 0 : 1;
    }
}
