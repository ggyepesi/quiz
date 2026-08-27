package datasource.graph;

/** How a configured relation participates in knowledge-graph traversal. */
public enum GraphExpansionPolicy {
    /** Materialize the relation normally; do not derive expansion coverage. */
    NONE("None"),
    /** Present newly reached target nodes for explicit user-selected expansion. */
    CURATED("Curated frontier");

    private final String label;

    GraphExpansionPolicy(String label) {
        this.label = label;
    }

    @Override public String toString() {
        return label;
    }
}
