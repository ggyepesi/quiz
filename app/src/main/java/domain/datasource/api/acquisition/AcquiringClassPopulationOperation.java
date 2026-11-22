package domain.datasource.api.acquisition;

import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.PreparedSourceOperation;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceRecipe;

/**
 * A class-population operation that reads a bounded set of source identities itself.
 *
 * <p>This is the sibling of {@link ClassPopulationOperation}: that operation adapts a
 * declarative relation or explicit list into the established extraction query; this one
 * first executes its own observable source query and then hands the returned identities
 * to that same extraction boundary.
 */
public interface AcquiringClassPopulationOperation extends DatasourceOperation {

    PopulationAcquisition acquisition(SourceRecipe recipe);

    @Override default PreparedSourceOperation prepare(SourceBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is required");
        try {
            PopulationAcquisition acquisition = acquisition(binding.recipe());
            return new PreparedSourceOperation(binding.recipe().providerId(), displayName(),
                    PreparedSourceOperation.Execution.ACQUIRE,
                    "Load " + acquisition.displayName(), binding.recipe().parameters(),
                    acquisition);
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
