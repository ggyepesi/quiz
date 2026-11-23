package domain.datasource.api;

import java.util.List;

/** A separately configurable capability of a datasource provider. */
public interface DatasourceOperation {
    String id();
    String displayName();
    BindingScope scope();
    List<ParameterDescriptor> parameters();
    SourceValueSchema outputSchema();

    /** The short label of one configured recipe of this operation, as a list child or
     *  tree node names it. Operations whose parameters identify the recipe (a table, an
     *  infobox row) override this; every other recipe is named by the operation. */
    default String summary(SourceBinding binding) { return displayName(); }

    /** Source records an execution consumes, distinct from recipe parameters. */
    default List<SourceReferenceSchema> inputReferences() { return List.of(); }

    /** Interpret one durable recipe. Providers override when their parameters form a
     * typed executable specification; callers never decode provider keys themselves. */
    default PreparedSourceOperation prepare(SourceBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is required");
        return new PreparedSourceOperation(binding.recipe().providerId(), displayName(),
                PreparedSourceOperation.Execution.RETAIN, displayName(),
                binding.recipe().parameters(), null);
    }
}
