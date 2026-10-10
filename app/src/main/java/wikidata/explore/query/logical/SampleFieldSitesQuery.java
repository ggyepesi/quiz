package wikidata.explore.query.logical;

import wikidata.explore.model.FieldSampleContext;
import wikidata.explore.query.result.TableQueryResult;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Samples one shared owned field over every selected production site. */
public final class SampleFieldSitesQuery implements Query<TableQueryResult> {
    private final List<FieldSampleContext> contexts;
    private final int limit;

    public SampleFieldSitesQuery(List<FieldSampleContext> contexts, int limit) {
        this.contexts = contexts == null ? List.of() : List.copyOf(contexts);
        this.limit = Math.max(1, limit);
    }

    @Override public String purpose() { return "Sample selected field at owner sites"; }

    @Override public String skeleton() {
        return "for each owner site -> sample owner instances -> sample field values";
    }

    @Override public Map<String, String> parameters() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ownerSites", String.valueOf(contexts.size()));
        if (!contexts.isEmpty()) values.put("field", contexts.getFirst().field().name());
        return values;
    }

    @Override public TableQueryResult execute(QueryContext context) throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        for (FieldSampleContext site : contexts) {
            TableQueryResult sampled = new SampleFieldQuery(site, limit).execute(context);
            String label = site.ownedSite() == null ? site.ownerClass().className()
                    : site.ownedSite().ownerClass() + "." + site.ownedSite().ownerField();
            for (List<Object> row : sampled.rows()) {
                List<Object> copy = new ArrayList<>(row);
                if (copy.size() > 1) copy.set(1, label + " — " + copy.get(1));
                rows.add(List.copyOf(copy));
            }
        }
        return new TableQueryResult(
                List.of("Owner QID", "Owner site and label", "Value", "Value label"),
                rows);
    }

    @Override public int rowCount(TableQueryResult result) {
        return result == null ? 0 : result.size();
    }

    @Override public String summary(TableQueryResult result) {
        return rowCount(result) + " values across " + contexts.size() + " owner site(s)";
    }
}
