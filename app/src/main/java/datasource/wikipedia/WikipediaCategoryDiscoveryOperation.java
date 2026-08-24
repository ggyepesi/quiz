package datasource.wikipedia;

import datasource.EntityRef;
import datasource.api.BindingScope;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.SourceBinding;
import datasource.api.PreparedSourceOperation;
import datasource.api.discovery.DiscoveredSourceValue;
import datasource.api.discovery.SourceDiscoveryOperation;
import datasource.api.discovery.SourceDiscoveryRequest;
import datasource.api.discovery.SourceDiscoveryResult;
import wikidata.explore.query.logical.DiscoverWikipediaCategoriesQuery;
import wikidata.explore.query.result.TableQueryResult;
import work.Query;
import work.QueryContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Observed category discovery, adapted over the existing acquisition query. */
public final class WikipediaCategoryDiscoveryOperation implements SourceDiscoveryOperation {
    public static final String ID = "category";
    public static final String FAMILY = "wikipedia-category-field";
    public static final String TYPE_QID = "typeQid";
    public static final String SAMPLE_SIZE = "sampleSize";
    public static final String PATTERN = "pattern";
    public static final String POLICY = "policy";

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Wikipedia categories"; }
    @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
    @Override public List<ParameterDescriptor> parameters() {
        return List.of(
                new ParameterDescriptor(TYPE_QID, "Sample class QID",
                        ParameterDescriptor.Kind.TEXT, false, "", List.of(),
                        "Wikidata class used to select sample articles."),
                new ParameterDescriptor(SAMPLE_SIZE, "Sample size",
                        ParameterDescriptor.Kind.INTEGER, false, "8", List.of(),
                        "Number of source articles inspected."),
                new ParameterDescriptor(PATTERN, "Category pattern",
                        ParameterDescriptor.Kind.TEXT, true, "", List.of(),
                        "Category title containing one <value> placeholder."),
                new ParameterDescriptor(POLICY, "Candidate policy",
                        ParameterDescriptor.Kind.CHOICE, false, "REVIEW",
                        java.util.Arrays.stream(
                                wikidata.explore.model.CategoryCandidatePolicy.values())
                                .map(Enum::name).toList(),
                        "How discovered category members enter review."));
    }
    @Override public SourceValueSchema outputSchema() {
        return SourceValueSchema.collection(SourceValueKind.TEXT);
    }

    @Override public PreparedSourceOperation prepare(SourceBinding binding) {
        var rule = WikipediaDatasourceProvider.categoryRule(binding);
        if (rule == null) return new PreparedSourceOperation(FAMILY,
                "Wikipedia category field", PreparedSourceOperation.Execution.RETAIN,
                "Incomplete Wikipedia category recipe", Map.of(), null);
        return new PreparedSourceOperation(FAMILY, "Wikipedia category field",
                PreparedSourceOperation.Execution.ACQUIRE,
                binding.target().className() + "." + binding.target().fieldPath()
                        + " ← category ‘" + rule.pattern() + "’ (" + rule.policy() + ")",
                Map.of("input", "Versioned category memberships from linked Wikipedia pages",
                        "operation", "Match " + rule.pattern() + " using " + rule.policy(),
                        "output", "Retain matched values with category provenance"), rule);
    }

    @Override public Query<SourceDiscoveryResult> discover(SourceDiscoveryRequest request) {
        SourceDiscoveryRequest safe = request == null
                ? new SourceDiscoveryRequest(List.of(), Map.of()) : request;
        List<String> qids = safe.seeds().stream()
                .filter(ref -> "wikidata".equalsIgnoreCase(ref.namespace()))
                .map(EntityRef::id).toList();
        int sampleSize = integer(safe.parameter(SAMPLE_SIZE), 8);
        DiscoverWikipediaCategoriesQuery delegate = qids.isEmpty()
                ? new DiscoverWikipediaCategoriesQuery(safe.parameter(TYPE_QID), sampleSize)
                : new DiscoverWikipediaCategoriesQuery(qids);
        return new CategoryQuery(delegate);
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private record CategoryQuery(DiscoverWikipediaCategoriesQuery delegate)
            implements Query<SourceDiscoveryResult> {
        @Override public String purpose() { return delegate.purpose(); }
        @Override public String skeleton() { return delegate.skeleton(); }
        @Override public String queryType() { return delegate.queryType(); }
        @Override public String description() { return delegate.description(); }
        @Override public Map<String, String> parameters() { return delegate.parameters(); }

        @Override public SourceDiscoveryResult execute(QueryContext context) throws Exception {
            TableQueryResult result = delegate.execute(context);
            List<DiscoveredSourceValue> values = new ArrayList<>();
            if (result != null) {
                for (List<Object> row : result.rows()) {
                    String value = cell(row, 0);
                    if (value.isBlank()) continue;
                    values.add(new DiscoveredSourceValue(
                            value, number(row, 1), cell(row, 2)));
                }
            }
            return new SourceDiscoveryResult(values, delegate.seedCount());
        }

        @Override public int rowCount(SourceDiscoveryResult result) {
            return result == null ? 0 : result.values().size();
        }

        @Override public String summary(SourceDiscoveryResult result) {
            return rowCount(result) + " categories";
        }

        private static String cell(List<Object> row, int index) {
            return row != null && index >= 0 && index < row.size()
                    && row.get(index) != null ? String.valueOf(row.get(index)) : "";
        }

        private static int number(List<Object> row, int index) {
            if (row != null && index >= 0 && index < row.size()
                    && row.get(index) instanceof Number number) {
                return number.intValue();
            }
            try { return Integer.parseInt(cell(row, index)); }
            catch (NumberFormatException ignored) { return 0; }
        }
    }
}
