package datasource.graph;

import datasource.graph.constraint.GraphEvidenceCondition;

import java.util.List;

/**
 * Authored linear discovery graph. A start source and a node's materialization are
 * independent: configured identities may start a traversal without becoming served
 * population members.
 */
public record GraphDiscoveryConfiguration(StartNode startNode, List<NextNode> nextNodes) {
    public enum NodeUse {
        INTERMEDIATE_ONLY,
        CLASS_POPULATION
    }

    /** QIDs come from the named class configuration; the graph owns no second QID list. */
    public record StartNode(String qidSourceClass, NodeUse use) {
        public StartNode {
            qidSourceClass = required(qidSourceClass, "Start-node QID source class is required");
            use = use == null ? NodeUse.INTERMEDIATE_ONLY : use;
        }
    }

    /** One node reached from the preceding node through its single incoming graph edge. */
    public record NextNode(
            GraphRelation property,
            GraphTraversalDirection directionFromPrevious,
            NodeUse use,
            String populationClass,
            GraphEvidenceCondition evidenceCondition) {
        public NextNode {
            if (property == null) throw new IllegalArgumentException("Edge property is required");
            if (directionFromPrevious == null) {
                throw new IllegalArgumentException("Edge property direction is required");
            }
            use = use == null ? NodeUse.INTERMEDIATE_ONLY : use;
            populationClass = populationClass == null ? "" : populationClass.trim();
            if (use == NodeUse.CLASS_POPULATION && populationClass.isBlank()) {
                throw new IllegalArgumentException(
                        "Choose the class whose population receives the reached entities");
            }
            if (use == NodeUse.INTERMEDIATE_ONLY) populationClass = "";
        }
    }

    public GraphDiscoveryConfiguration {
        if (startNode == null) throw new IllegalArgumentException("Start node is required");
        nextNodes = nextNodes == null ? List.of() : List.copyOf(nextNodes);
        if (nextNodes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Graph nodes cannot contain null");
        }
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
