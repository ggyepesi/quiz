package datasource.graph;

/**
 * What the expansion ledger needs to know about a configured edge, whatever
 * materializes it.
 *
 * <p>Two things are edges. A {@link GraphExpansionPattern} materializes one through a
 * statement class with a role field at each end; a {@link GraphTraversalStep}
 * materializes one through a typed field on the source class. Coverage does not care:
 * it records which node a named edge has expanded and which it has merely reached, and
 * that is decided by the four members below.
 *
 * <p>Kept as the intersection rather than a base with optional halves. A statement
 * pattern has no meaningful traversal policy and a field step has no target role field,
 * so a union type would carry a blank for whichever it is not, and every consumer would
 * have to know which blanks to expect.
 */
public interface GraphEdgeDefinition {

    /** Stable identity of this edge, as coverage records it. */
    String id();

    String sourceNodeClass();

    String targetNodeClass();

    GraphRelation relation();

    GraphTraversalDirection direction();
}
