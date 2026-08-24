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
import datasource.api.PreparedSourceOperation;

import java.util.List;

/** DBpedia capabilities available to both configuration applications. */
public final class DbpediaDatasourceProvider implements DatasourceProvider {
    public static final String ID = "dbpedia";
    public static final String PROPERTY = "property";

    public static final String FAMILY_FIELD = "dbpedia-field";

    /** Compatibility reader for pre-plan callers. */
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
        @Override public PreparedSourceOperation prepare(SourceBinding binding) {
            String property = DbpediaDatasourceProvider.property(binding);
            // Described rather than thrown: see the infobox operation. An unfinished
            // recipe must not stop a run that has nothing else wrong with it.
            if (property == null) return new PreparedSourceOperation(
                    FAMILY_FIELD, "DBpedia field",
                    PreparedSourceOperation.Execution.RETAIN,
                    "Incomplete DBpedia property recipe at "
                            + binding.target().className() + "."
                            + binding.target().fieldPath(),
                    java.util.Map.of(), null);
            PropertySpec spec = new PropertySpec(property,
                    binding.target().slot() == SourceBindingSlot.FALLBACK_FIELD_VALUE);
            return new PreparedSourceOperation(FAMILY_FIELD, "DBpedia field",
                    PreparedSourceOperation.Execution.ACQUIRE,
                    binding.target().className() + "." + binding.target().fieldPath()
                            + " ← dbp:" + property,
                    java.util.Map.of(
                            "input", "Reachable entity IDs joined through owl:sameAs",
                            "operation", "Read dbp:" + property + " in batches of 100",
                            "output", "Merge values into the configured model field"), spec);
        }
    }

    public record PropertySpec(String property, boolean fillOnlyMissing) { }
}
