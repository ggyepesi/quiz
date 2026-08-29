package wikidata.explore.demo.closure;

import java.util.List;

/** Retrieves complete two-hop edges for one frontier partition. */
@FunctionalInterface
public interface SharedPopulationExpansionSource {
    SharedPopulationExpansion expand(
            SharedPopulationClosureConfig config,
            List<String> sourceQids) throws Exception;

    /** The physical request shown in the batch log, empty for an in-memory source. */
    default String request(
            SharedPopulationClosureConfig config,
            List<String> sourceQids) {
        return "";
    }
}
