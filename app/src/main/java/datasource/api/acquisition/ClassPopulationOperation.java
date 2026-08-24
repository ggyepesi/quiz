package datasource.api.acquisition;

import datasource.api.DatasourceOperation;
import datasource.api.SourceRecipe;

/**
 * A class-population capability that exposes its logical selection separately from its
 * physical query. Generation consumes the selection at its established extraction
 * boundary, preserving model filters, ranking, batching, caching and checkpoints.
 */
public interface ClassPopulationOperation extends DatasourceOperation {
    PopulationSelection selection(SourceRecipe recipe);
}
