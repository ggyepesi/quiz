package datasource.api.discovery;

import java.util.List;

/** Common discovery result consumed by shared source pickers. */
public record SourceDiscoveryResult(
        List<DiscoveredSourceValue> values,
        int seedCount) {

    public SourceDiscoveryResult {
        values = values == null ? List.of() : List.copyOf(values);
        seedCount = Math.max(0, seedCount);
    }
}
