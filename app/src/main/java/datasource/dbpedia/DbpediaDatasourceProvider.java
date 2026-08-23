package datasource.dbpedia;

import datasource.api.BindingScope;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceReferenceSchema;
import datasource.api.SourceValueKind;
import datasource.api.SourceValueSchema;

import java.util.List;

/** DBpedia capabilities available to both configuration applications. */
public final class DbpediaDatasourceProvider implements DatasourceProvider {
    public static final String ID = "dbpedia";
    public static final String PROPERTY = "property";

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
