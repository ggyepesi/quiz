package datasource.graph;

import datasource.graph.constraint.GraphEvidenceCondition;

import java.util.List;

/**
 * Authored linear discovery graph. A start source and a node's materialization are
 * independent: configured identities may start a traversal without becoming served
 * population members.
 */
public record GraphDiscoveryConfiguration(String name, StartNode startNode, List<NextNode> nextNodes) {
    public GraphDiscoveryConfiguration(StartNode startNode, List<NextNode> nextNodes) {
        this("GraphConstraint", startNode, nextNodes);
    }
    public enum NodeUse {
        INTERMEDIATE_ONLY,
        CLASS_POPULATION
    }

    /**
     * A class start reads QIDs from its currently loaded instances. A population start
     * reads the exact QIDs owned by that saved population selection.
     */
    public record StartNode(String qidSourceClass, String populationSelection, NodeUse use) {
        public StartNode(String qidSourceClass, NodeUse use) {
            this(qidSourceClass, "", use);
        }
        public StartNode {
            qidSourceClass = qidSourceClass == null ? "" : qidSourceClass.trim();
            populationSelection = populationSelection == null ? "" : populationSelection.trim();
            if (qidSourceClass.isBlank() == populationSelection.isBlank()) {
                throw new IllegalArgumentException(
                        "Choose exactly one graph start: a loaded class or a saved population");
            }
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
        // Models saved before graph constraints were named load as the explicit legacy
        // default; the editor requires a Java-style authored name on the next Apply.
        name = name == null || name.isBlank() ? "GraphConstraint" : name.trim();
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
