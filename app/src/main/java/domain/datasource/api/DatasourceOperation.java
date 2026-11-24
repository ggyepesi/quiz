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

    /**
     * Model classes and fields named by one configured recipe.
     *
     * <p>A recipe is provider-owned grammar.  Model navigation, validation and rename
     * therefore ask the operation for these typed references instead of decoding its
     * parameter map.  Source-native inputs remain {@link #inputReferences()}; the
     * model-layer inventory separately resolves prepared input requirements such as an
     * article correspondence.</p>
     */
    default List<ModelConfigurationReference> configurationReferences(
            SourceBinding binding) {
        return List.of();
    }

    /** Interpret one durable recipe. Providers override when their parameters form a
     * typed executable specification; callers never decode provider keys themselves. */
    default PreparedSourceOperation prepare(SourceBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is required");
        return new PreparedSourceOperation(binding.recipe().providerId(), displayName(),
                PreparedSourceOperation.Execution.RETAIN, displayName(),
                binding.recipe().parameters(), null);
    }
}
