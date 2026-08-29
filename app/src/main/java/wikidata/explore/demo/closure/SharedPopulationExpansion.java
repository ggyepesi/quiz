package wikidata.explore.demo.closure;

import java.util.List;

/** Detached map result for one frontier partition. */
public record SharedPopulationExpansion(
        List<SharedPopulationMember> members,
        List<SharedPopulationEdge> edges,
        int rejectedTargetCount,
        boolean resourceLimitReached) {

    public SharedPopulationExpansion {
        members = members == null ? List.of() : List.copyOf(members);
        edges = edges == null ? List.of() : List.copyOf(edges);
        if (rejectedTargetCount < 0) {
            throw new IllegalArgumentException("rejectedTargetCount must be >= 0");
        }
    }

    public SharedPopulationExpansion(
            List<SharedPopulationMember> members,
            List<SharedPopulationEdge> edges) {
        this(members, edges, 0, false);
    }

    public static SharedPopulationExpansion empty() {
        return new SharedPopulationExpansion(List.of(), List.of(), 0, false);
    }
}
