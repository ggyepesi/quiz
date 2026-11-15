package datasource.wikidata;

import datasource.graph.GraphRelation;
import datasource.graph.GraphTraversalDirection;
import wikidata.WikidataIds;

/**
 * What one Wikidata relation-route step reads: a property between entities, the
 * statements of a property, or one qualifier of a statement.
 *
 * <p>The neutral {@link GraphRelation} carries an opaque relation id; this is the one
 * place that spells a Wikidata step kind into that id and reads it back. The route
 * spec, the graph acquisition and the route editor all go through it.
 */
public record WikidataRouteRelation(Kind kind, String pid) {

    public enum Kind {
        PROPERTY("Property", ""),
        STATEMENT("Statement", "statement:"),
        QUALIFIER("Qualifier", "qualifier:");

        private final String label;
        private final String prefix;

        Kind(String label, String prefix) {
            this.label = label;
            this.prefix = prefix;
        }

        @Override public String toString() { return label; }
    }

    public WikidataRouteRelation {
        if (kind == null) throw new IllegalArgumentException("Relation kind is required");
        pid = pid == null ? "" : pid.trim().toUpperCase();
        if (!WikidataIds.isPid(pid)) {
            throw new IllegalArgumentException("A route step needs a valid property PID: "
                    + (pid.isBlank() ? "(blank)" : pid));
        }
    }

    /** The step this Wikidata relation reads, or null when it is not one. */
    public static WikidataRouteRelation parse(GraphRelation relation) {
        if (relation == null
                || !WikidataDatasourceProvider.ID.equals(relation.providerId())) return null;
        String id = relation.relationId();
        for (Kind kind : new Kind[] { Kind.STATEMENT, Kind.QUALIFIER }) {
            if (id.startsWith(kind.prefix)) {
                String pid = id.substring(kind.prefix.length()).trim().toUpperCase();
                return WikidataIds.isPid(pid) ? new WikidataRouteRelation(kind, pid) : null;
            }
        }
        return WikidataIds.isPid(id) ? new WikidataRouteRelation(Kind.PROPERTY, id) : null;
    }

    public GraphRelation relation() {
        return new GraphRelation(WikidataDatasourceProvider.ID, kind.prefix + pid);
    }

    /** Statements and qualifiers are read from their subject, so only forward. */
    public void requireDirection(GraphTraversalDirection direction) {
        if (kind != Kind.PROPERTY && direction != GraphTraversalDirection.OUTGOING) {
            throw new IllegalArgumentException(kind + " " + pid
                    + " steps support outgoing traversal only");
        }
    }
}
