package datasource.api;

import java.util.List;

/** A separately configurable capability of a datasource provider. */
public interface DatasourceOperation {
    String id();
    String displayName();
    BindingScope scope();
    List<ParameterDescriptor> parameters();
    SourceValueSchema outputSchema();

    /** Source records an execution consumes, distinct from recipe parameters. */
    default List<SourceReferenceSchema> inputReferences() { return List.of(); }
}
