package datasource.graph.store;

/** Whether a local empty adjacency is evidence or merely absence from the store. */
public enum GraphAdjacencyCoverage {
    UNKNOWN,
    COMPLETE,
    INCOMPLETE,
    UNAVAILABLE
}
