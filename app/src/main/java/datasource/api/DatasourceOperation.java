package datasource.api;

import datasource.api.acquisition.SourceAcquisitionOperation;
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

    /** Interpret one durable recipe. Providers override when their parameters form a
     * typed executable specification; callers never decode provider keys themselves. */
    default PreparedSourceOperation prepare(SourceBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is required");
        PreparedSourceOperation.Execution execution =
                this instanceof SourceAcquisitionOperation<?>
                        ? PreparedSourceOperation.Execution.ACQUIRE
                        : PreparedSourceOperation.Execution.RETAIN;
        return new PreparedSourceOperation(binding.recipe().providerId(), displayName(),
                execution, displayName(),
                binding.recipe().parameters(), null);
    }
}
