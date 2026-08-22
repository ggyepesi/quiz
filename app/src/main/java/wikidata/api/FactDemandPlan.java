package wikidata.api;

import java.util.List;

/**
 * The prospective fact needs of one run, as a thing that can be carried and asked.
 *
 * <p>This used to be {@code GenerationFactDemandPlan}, which both COMPILES the plan by asking
 * each transform step what it will need and IS the plan those steps are then handed. Two jobs
 * in one type, and between them a cycle: generation ran the transform steps, and the transform
 * steps named a generation type in their signatures. Compiling stays where it knows the steps;
 * the plan itself belongs beside the demands it holds, low enough for either to name.
 */
public final class FactDemandPlan {

    private final List<FactDemand> demands;

    public FactDemandPlan(List<FactDemand> demands) {
        this.demands = demands == null ? List.of() : List.copyOf(demands);
    }

    public static FactDemandPlan empty() {
        return new FactDemandPlan(List.of());
    }

    public List<FactDemand> forClass(String className) {
        if (className == null) return List.of();
        return demands.stream().filter(d -> className.equals(d.targetClass())).toList();
    }

    public List<FactDemand> all() {
        return demands;
    }
}
