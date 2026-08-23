package datasource.api.acquisition;

import datasource.EntityRef;

import java.util.List;
import java.util.Map;

/** Source records to read plus the normalized parameters of the bound offering. */
public record SourceAcquisitionRequest(
        List<EntityRef> subjects,
        Map<String, String> parameters) {

    public SourceAcquisitionRequest {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    public String parameter(String key) {
        return parameters.getOrDefault(key, "");
    }
}
