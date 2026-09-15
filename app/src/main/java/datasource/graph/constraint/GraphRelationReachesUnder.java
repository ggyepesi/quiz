package datasource.graph.constraint;

import datasource.EntityRef;
import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;

/**
 * Matches when traversing a relation reaches {@code entity} itself, or anything that
 * {@code entity} generalises through {@code via}.
 *
 * <p>The condition {@link GraphRelationReaches} cannot answer this one. Asked whether a
 * historical office belongs to a polity, equality made the question "is its jurisdiction
 * a country, an empire or a historical country" — three values that describe a modern
 * nation state — and rejected the Dey of Tunis (an eyalet), the exarch of Ravenna (an
 * exarchate), the Prince-Bishop (a spiritual territory) and the President of the
 * Confederate States (a historical unrecognized state). Every one of those IS a polity;
 * none of them equals one of three names. Enumerating more names only postpones the
 * khanate and the margraviate.
 *
 * <p>The source states the generalisation, so the test follows it: {@code via} is walked
 * OUTWARD from what the relation reached — from the specific towards the general, which
 * is what "under" means — for at most {@code maximumDepth} hops.
 */
public record GraphRelationReachesUnder(
        GraphRelation relation,
        GraphTraversalDirection direction,
        EntityRef entity,
        GraphRelation via,
        int maximumDepth) implements GraphNodeCondition {

    /** Beyond this, a generalisation hierarchy stops discriminating: everything is an
     *  entity eventually. Deep enough for eyalet → administrative territorial entity →
     *  polity without paying for the whole upper ontology. */
    public static final int DEFAULT_MAXIMUM_DEPTH = 4;

    public GraphRelationReachesUnder {
        if (relation == null || direction == null || entity == null || via == null) {
            throw new IllegalArgumentException(
                    "Reached relation, direction, entity and generalising relation are required");
        }
        if (maximumDepth < 1) {
            throw new IllegalArgumentException(
                    "Generalisation depth must be at least one");
        }
    }

    public static GraphRelationReachesUnder of(
            GraphRelation relation,
            GraphTraversalDirection direction,
            EntityRef entity,
            GraphRelation via) {
        return new GraphRelationReachesUnder(
                relation, direction, entity, via, DEFAULT_MAXIMUM_DEPTH);
    }
}
