package datasource.pageviews;

import datasource.api.BindingScope;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;
import datasource.api.ParameterDescriptor;
import datasource.api.PreparedSourceOperation;
import datasource.api.SourceBinding;
import datasource.api.SourceInputRequirement;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;

import java.util.List;

/**
 * Wikimedia Pageviews: how many human readers opened an entity's English Wikipedia
 * article over a window of complete months.
 *
 * <p>It reads the article through the entity's one retained Wikipedia correspondence, so
 * it adds no second way of finding the article. The value is a measurement that changes
 * over time; the recipe's window says what was measured, and the value changes only when
 * the domain is generated again.
 */
public final class PageviewsDatasourceProvider implements DatasourceProvider {
    public static final String ID = "wikimedia-pageviews";
    public static final String ARTICLE_VIEWS = "article-views";
    public static final String MONTHS = "months";
    public static final String FAMILY_FIELD = "wikimedia-pageviews-field";
    static final int DEFAULT_MONTHS = 12;
    static final int MAXIMUM_MONTHS = 120;

    private final List<DatasourceOperation> operations = List.of(new ArticleViews());

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Wikimedia Pageviews"; }
    @Override public List<? extends DatasourceOperation> operations() { return operations; }

    /** What one binding asks for: the number of complete months ending last month. */
    public record ArticleViewsSpec(int months) {
        public ArticleViewsSpec {
            if (months < 1 || months > MAXIMUM_MONTHS) {
                throw new IllegalArgumentException("Pageviews window must be 1–"
                        + MAXIMUM_MONTHS + " complete months, not " + months);
            }
        }
    }

    private record ArticleViews() implements DatasourceOperation {
        @Override public String id() { return ARTICLE_VIEWS; }
        @Override public String displayName() { return "English Wikipedia article views"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(new ParameterDescriptor(MONTHS, "Complete months",
                    ParameterDescriptor.Kind.INTEGER, false,
                    String.valueOf(DEFAULT_MONTHS), List.of(),
                    "Total views by human readers over this many complete months, "
                            + "ending with last month."));
        }
        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.QUANTITY, false, "");
        }
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            if (binding == null) throw new IllegalArgumentException("binding is required");
            String text = binding.recipe().parameter(MONTHS);
            int months;
            try {
                months = text.isBlank() ? DEFAULT_MONTHS : Integer.parseInt(text);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException(
                        "Pageviews months must be a whole number, not '" + text + "'");
            }
            ArticleViewsSpec spec = new ArticleViewsSpec(months);
            return new PreparedSourceOperation(FAMILY_FIELD, "Wikimedia Pageviews",
                    PreparedSourceOperation.Execution.ACQUIRE,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " ← English Wikipedia views, last " + months
                            + " complete month(s)",
                    java.util.Map.of(
                            "input", "Wikipedia article linked from the entity ID",
                            "operation", "Sum monthly user views over " + months
                                    + " complete month(s)",
                            "output", "Fill the configured number field"),
                    spec, List.of(new SourceInputRequirement(datasource.EntityRef.WIKIDATA,
                            SourceInputRequirement.Kind.ARTICLE_CORRESPONDENCE)));
        }
    }
}
