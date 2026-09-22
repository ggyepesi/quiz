package wikidata.explore.model;

import datasource.graph.GraphDiscoveryConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares a class whose instances are discovered by traversing a configured relation
 * from a start population, and classified by evidence tests.
 *
 * <p>The sibling of {@link StatementClassSource} and {@link AggregateClassSource}: the
 * configuration a class of its kind needs, and nothing else. What it deliberately does
 * NOT hold is a name. A graph constraint used to carry its own, which was simultaneously
 * the identity of its annotation set — the result file is keyed by it — and a free-text
 * field an editor rewrote, so a run saved as PositionFilter came to sit beside a model
 * calling itself GraphConstraint with nothing able to notice. The name is now the class
 * name, which every other construct already holds, and {@code declarationId} is the
 * identity underneath it that a rename does not move.
 *
 * <p>{@link #configurationFor} is the one place ⟨class, source⟩ becomes the record the
 * executor takes, so the run's name and the class's name cannot be two facts.
 */
public final class GraphClassSource {
    private GraphDiscoveryConfiguration.StartNode startNode;
    private final List<GraphDiscoveryConfiguration.NextNode> nextNodes = new ArrayList<>();

    public GraphClassSource() {}

    public GraphClassSource(GraphDiscoveryConfiguration.StartNode startNode,
                            List<GraphDiscoveryConfiguration.NextNode> nextNodes) {
        startNode(startNode);
        nextNodes(nextNodes);
    }

    public GraphDiscoveryConfiguration.StartNode startNode() { return startNode; }
    public void startNode(GraphDiscoveryConfiguration.StartNode value) { startNode = value; }

    public List<GraphDiscoveryConfiguration.NextNode> nextNodes() { return nextNodes; }
    public void nextNodes(List<GraphDiscoveryConfiguration.NextNode> value) {
        nextNodes.clear();
        if (value != null) value.stream().filter(java.util.Objects::nonNull)
                .forEach(nextNodes::add);
    }

    /**
     * The class whose population the terminal node produces.
     *
     * <p>Read from the stored {@link GraphDiscoveryConfiguration.NodeUse}, never guessed
     * from a node's label — the fact is stored, so asking anything that merely agrees
     * with it is a second discovery path.
     */
    public String outputClassName() {
        return nextNodes.stream()
                .filter(node -> node.use() == GraphDiscoveryConfiguration.NodeUse.CLASS_POPULATION)
                .map(GraphDiscoveryConfiguration.NextNode::populationClass)
                .reduce((first, second) -> second)
                .orElse("");
    }

    /** Whether this source has everything a run needs. */
    public boolean configured() {
        return startNode != null && !nextNodes.isEmpty() && !outputClassName().isBlank();
    }

    /** The record the executor takes, named by the class that declares it. */
    public GraphDiscoveryConfiguration configurationFor(String className) {
        return new GraphDiscoveryConfiguration(className, startNode, List.copyOf(nextNodes));
    }

    public GraphClassSource copy() {
        return new GraphClassSource(startNode, nextNodes);
    }

    @Override public boolean equals(Object other) {
        return other instanceof GraphClassSource source
                && java.util.Objects.equals(startNode, source.startNode)
                && java.util.Objects.equals(nextNodes, source.nextNodes);
    }

    @Override public int hashCode() {
        return java.util.Objects.hash(startNode, nextNodes);
    }
}
