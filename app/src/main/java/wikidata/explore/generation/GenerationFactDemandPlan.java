package wikidata.explore.generation;

import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.DisambiguationPrune;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.ReferentFieldLoad;

import java.util.ArrayList;
import java.util.List;

/** Compiles the prospective fact needs that can be known before discovery. */
public final class GenerationFactDemandPlan {
    private final List<FactDemand> demands;

    private GenerationFactDemandPlan(List<FactDemand> demands) {
        this.demands = List.copyOf(demands);
    }

    public static GenerationFactDemandPlan compile(GeneratedProjectModel model) {
        if (model == null) return new GenerationFactDemandPlan(List.of());
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
        for (var clazz : model.classes()) {
            if (clazz == null) continue;
            planned.add(FactDemand.metadata(
                    "final label hydration", clazz.className(),
                    List.of(FactDemand.EntityMetadata.LABEL),
                    "resolve placeholder QIDs after the graph closes"));
            planned.add(FactDemand.metadata(
                    "alias coverage", clazz.className(),
                    List.of(FactDemand.EntityMetadata.ALIASES),
                    "carry alternate names on any entity response"));
        }
        return new GenerationFactDemandPlan(planned);
    }

    public List<FactDemand> forClass(String className) {
        if (className == null) return List.of();
        return demands.stream()
                .filter(d -> className.equals(d.targetClass()))
                .toList();
    }

    public List<FactDemand> all() {
        return demands;
    }
}
