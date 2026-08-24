package datasource.api;

import java.util.Map;
import java.util.List;

/**
 * One binding interpreted by the operation that owns its grammar.
 *
 * <p>Providers prepare once; execution, explanations and reporting consume this same
 * value. The typed configuration deliberately remains provider-owned while the common
 * fields make orchestration provider-neutral.
 */
public record PreparedSourceOperation(
        String familyId,
        String familyName,
        Execution execution,
        String description,
        Map<String, String> details,
        Object configuration,
        List<SourceInputRequirement> inputRequirements) {

    public enum Execution { ACQUIRE, RETAIN }

    public PreparedSourceOperation {
        familyId = familyId == null || familyId.isBlank() ? "other" : familyId;
        familyName = familyName == null || familyName.isBlank() ? familyId : familyName;
        execution = execution == null ? Execution.RETAIN : execution;
        description = description == null ? "" : description;
        details = details == null ? Map.of() : Map.copyOf(details);
        inputRequirements = inputRequirements == null ? List.of()
                : List.copyOf(inputRequirements);
    }

    public PreparedSourceOperation(String familyId, String familyName,
            Execution execution, String description, Map<String, String> details,
            Object configuration) {
        this(familyId, familyName, execution, description, details, configuration,
                List.of());
    }

    public <T> T configuration(Class<T> type) {
        if (type == null || !type.isInstance(configuration)) return null;
        return type.cast(configuration);
    }
}
