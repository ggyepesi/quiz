package wikidata.explore.generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordered, duplicate-safe catalogue of external acquisition families for one run.
 *
 * <p>The standard catalogue is rebuilt per invocation. Callers may inject a different
 * catalogue through the acquisition seam; this is not a mutable runtime plugin registry.
 */
public final class ExternalSourceFamilyRegistry {
    private final List<ExternalSourceFamily> families;
    private final Map<String, ExternalSourceFamily> byId;

    public ExternalSourceFamilyRegistry(List<? extends ExternalSourceFamily> families) {
        LinkedHashMap<String, ExternalSourceFamily> index = new LinkedHashMap<>();
        if (families != null) for (ExternalSourceFamily family : families) {
            if (family == null || family.id() == null || family.id().isBlank()) {
                throw new IllegalArgumentException("External source family id is required");
            }
            if (index.putIfAbsent(family.id(), family) != null) {
                throw new IllegalArgumentException(
                        "Duplicate external source family: " + family.id());
            }
        }
        this.byId = Map.copyOf(index);
        this.families = List.copyOf(index.values());
    }

    public List<ExternalSourceFamily> families() { return families; }
    public ExternalSourceFamily require(String id) {
        ExternalSourceFamily family = byId.get(id);
        if (family == null) throw new IllegalArgumentException(
                "Unknown external source family: " + id);
        return family;
    }
}
