package datasource.api.discovery;

import datasource.EntityRef;

import java.util.List;
import java.util.Map;

/** Provider-neutral seeds and operation parameters for source structure discovery. */
public record SourceDiscoveryRequest(
        List<EntityRef> seeds,
        Map<String, String> parameters) {

    public SourceDiscoveryRequest {
        seeds = seeds == null ? List.of() : List.copyOf(seeds);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public String parameter(String key) {
        return parameters.getOrDefault(key, "");
    }
}
