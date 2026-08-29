package wikidata.explore.demo.closure;

import batch.WorkDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent commit store for completed closure map units.
 *
 * <p>The batch journal remembers that a unit completed; this store remembers the value
 * it produced. Both are deliberately in memory in the demo, but keeping the boundaries
 * separate makes resume honest and mirrors what a durable implementation must persist.
 */
final class InMemoryClosureResultStore {
    private final Map<String, Map<String, SharedPopulationExpansion>> runs =
            new LinkedHashMap<>();

    synchronized void commit(
            String runKey, WorkDescriptor descriptor, SharedPopulationExpansion result) {
        runs.computeIfAbsent(runKey, ignored -> new LinkedHashMap<>())
                .put(descriptor.key(), result);
    }

    synchronized List<SharedPopulationExpansion> results(String runKey) {
        Map<String, SharedPopulationExpansion> results = runs.get(runKey);
        return results == null ? List.of() : List.copyOf(results.values());
    }

    synchronized void reset(String runKey) {
        runs.remove(runKey);
    }
}
