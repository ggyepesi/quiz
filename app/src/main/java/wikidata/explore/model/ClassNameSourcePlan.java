package wikidata.explore.model;

import datasource.api.BindingScope;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceExecutionPlan;
import datasource.wikidata.WikidataDatasourceProvider;

import java.util.LinkedHashSet;
import java.util.Set;

/** Class populations whose configured Wikidata name declarations execution retains. */
public final class ClassNameSourcePlan {
    private ClassNameSourcePlan() { }

    public static Set<String> labels(SourceExecutionPlan plan) {
        return classes(plan, SourceBindingSlot.CLASS_LABEL,
                WikidataDatasourceProvider.LABEL);
    }

    public static Set<String> aliases(SourceExecutionPlan plan) {
        return classes(plan, SourceBindingSlot.CLASS_ALIASES,
                WikidataDatasourceProvider.ALIASES);
    }

    private static Set<String> classes(SourceExecutionPlan plan,
            SourceBindingSlot slot, String operation) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (plan == null) return Set.of();
        plan.steps(BindingScope.CLASS_NAMES).stream()
                .filter(step -> step.target().slot() == slot)
                .filter(step -> WikidataDatasourceProvider.ID.equals(
                        step.recipe().providerId()))
                .filter(step -> operation.equals(step.recipe().operationId()))
                .map(step -> step.target().className())
                .forEach(result::add);
        return Set.copyOf(result);
    }
}
