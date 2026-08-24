package datasource.dbpedia;

import datasource.api.BindingScope;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceReferenceSchema;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceRecipe;

import java.util.List;

/** DBpedia capabilities available to both configuration applications. */
public final class DbpediaDatasourceProvider implements DatasourceProvider {
    public static final String ID = "dbpedia";
    public static final String PROPERTY = "property";

    /** The DBpedia property this field binding reads, or {@code null} when the
     * binding is not an executable primary/fallback DBpedia field value. */
    public static String property(SourceBinding binding) {
        if (binding == null || binding.target().scope() != BindingScope.FIELD_VALUE)
            return null;
        SourceBindingSlot slot = binding.target().slot();
        if (slot != SourceBindingSlot.PRIMARY_FIELD_VALUE
                && slot != SourceBindingSlot.FALLBACK_FIELD_VALUE) return null;
        SourceRecipe recipe = binding.recipe();
        if (!ID.equals(recipe.providerId()) || !PROPERTY.equals(recipe.operationId()))
            return null;
        String value = recipe.parameter("property").trim();
        return value.isBlank() ? null : value;
    }

    private final List<DatasourceOperation> operations = List.of(new PropertyValue());

    @Override public String id() { return ID; }
    @Override public String displayName() { return "DBpedia"; }
    @Override public List<? extends DatasourceOperation> operations() { return operations; }

    private record PropertyValue() implements DatasourceOperation {
        @Override public String id() { return PROPERTY; }
        @Override public String displayName() { return "DBpedia property"; }
        @Override public BindingScope scope() { return BindingScope.FIELD_VALUE; }
        @Override public List<ParameterDescriptor> parameters() {
            return List.of(ParameterDescriptor.reference(
                    "property", "Property", true, "",
                    "The DBpedia property supplying this field.",
                    new SourceReferenceSchema(ID,
                            SourceReferenceSchema.Kind.PROPERTY, false)));
        }
        @Override public SourceValueSchema outputSchema() {
            return new SourceValueSchema(SourceValueKind.MODEL_VALUE, true, "");
        }
    }
}
