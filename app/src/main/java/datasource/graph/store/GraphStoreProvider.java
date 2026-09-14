package datasource.graph.store;

/** Opens the local graph owned by one execution context. */
@FunctionalInterface
public interface GraphStoreProvider {
    LocalGraphStore open() throws Exception;
}
