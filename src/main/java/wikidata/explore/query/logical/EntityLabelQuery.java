package wikidata.explore.query.logical;

import wikidata.WikidataBinding;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.query.template.sparql.SparqlQueries;

import java.util.LinkedHashMap;
import java.util.Map;

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
        String sparql = SparqlQueries.entityLabel(qid, "en");

        for (WikidataBinding b : context.sparql().query(sparql)) {
            return b.value("label");
        }

        return null;
    }

    @Override
    public int rowCount(String result) {
        return result == null ? 0 : 1;
    }
}
