package domain.datasource.wikipedia;

import domain.datasource.api.SourceBinding;

/** One readable description of a configured Wikipedia evidence child: the label its own
 * operation gives it, so a new operation names its recipes without a change here. */
public final class WikipediaEvidenceRecipes {
    private static final WikipediaDatasourceProvider WIKIPEDIA = new WikipediaDatasourceProvider();

    private WikipediaEvidenceRecipes() { }

    public static String describe(SourceBinding binding) {
        if (binding == null) return "Incomplete evidence recipe";
        String provider = binding.recipe().providerId();
        String operation = binding.recipe().operationId();
        if (WikipediaDatasourceProvider.ID.equals(provider)) {
            return WIKIPEDIA.operation(operation)
                    .map(value -> value.summary(binding))
                    .orElse(provider + " — " + operation);
        }
        return provider + " — " + operation;
    }
}
