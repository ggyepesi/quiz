package wikidata.explore.generation;

import wikidata.api.FactDemand;
import wikidata.api.FactDemandPlan;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.DisambiguationPrune;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.ReferentFieldLoad;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles the prospective fact needs that can be known before discovery.
 *
 * <p>Only the compiling lives here, because only the compiling needs to know the transform
 * steps. What it produces is a {@link FactDemandPlan}, which those same steps are handed —
 * and which names nothing in this package, so the two stages no longer depend on each other.
 */
public final class GenerationFactDemandPlan {

    private GenerationFactDemandPlan() { }

    public static FactDemandPlan compile(GeneratedProjectModel model) {
        return compile(model, null);
    }

    public static FactDemandPlan compile(
            GeneratedProjectModel model,
            datasource.api.SourceExecutionPlan sourcePlan) {
        if (model == null) return FactDemandPlan.empty();
        List<FactDemand> planned = new ArrayList<>();
        planned.addAll(DisambiguationPrune.factDemands(model));
        planned.addAll(ReferentFieldLoad.factDemands(model));
        for (var recipe : ModelStatementReifications.derive(model)) {
            var load = recipe.load();
            planned.add(FactDemand.of(
                    "statement acquisition", load.entityType(),
                    List.of(load.propertyPid()),
                    "load " + load.statementType() + " statements and qualifiers"));
        }
        java.util.Set<String> labelClasses =
                sourcePlan == null ? model.classes().stream()
                        .filter(java.util.Objects::nonNull)
                        .map(c -> c.className()).collect(java.util.stream.Collectors.toSet())
                        : wikidata.explore.model.ClassNameSourcePlan.labels(sourcePlan);
        java.util.Set<String> aliasClasses =
                sourcePlan == null ? labelClasses
                        : wikidata.explore.model.ClassNameSourcePlan.aliases(sourcePlan);
        for (var clazz : model.classes()) {
            if (clazz == null) continue;
            if (labelClasses.contains(clazz.className())) {
                planned.add(FactDemand.metadata(
                        "final label hydration", clazz.className(),
                        List.of(FactDemand.EntityMetadata.LABEL),
                        "resolve placeholder QIDs after the graph closes"));
            }
            if (aliasClasses.contains(clazz.className())) {
                planned.add(FactDemand.metadata(
                        "alias coverage", clazz.className(),
                        List.of(FactDemand.EntityMetadata.ALIASES),
                        "carry configured alternate names on entity responses"));
            }
        }
        return new FactDemandPlan(planned);
    }
}
