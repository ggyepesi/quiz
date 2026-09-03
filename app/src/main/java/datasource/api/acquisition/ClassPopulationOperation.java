package datasource.api.acquisition;

import datasource.api.DatasourceOperation;
import datasource.api.PreparedSourceOperation;
import datasource.api.SourceBinding;
import datasource.api.SourceRecipe;

/**
 * A class-population capability that exposes its logical selection separately from its
 * physical query. Generation consumes the selection at its established extraction
 * boundary, preserving model filters, ranking, batching, caching and checkpoints.
 */
public interface ClassPopulationOperation extends DatasourceOperation {
    PopulationRequest selection(SourceRecipe recipe);

    @Override default PreparedSourceOperation prepare(SourceBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is required");
        try {
            PopulationRequest selection = selection(binding.recipe());
            return new PreparedSourceOperation(binding.recipe().providerId(), displayName(),
                    PreparedSourceOperation.Execution.ACQUIRE, displayName(),
                    binding.recipe().parameters(), selection);
        } catch (IllegalArgumentException incomplete) {
            String reason = incomplete.getMessage() == null
                    ? "invalid population parameters" : incomplete.getMessage();
            return new PreparedSourceOperation(binding.recipe().providerId(), displayName(),
                    PreparedSourceOperation.Execution.RETAIN,
                    "Incomplete population recipe at " + binding.target().className()
                            + ": " + reason,
                    java.util.Map.of("reason", reason), null);
        }
    }
}
