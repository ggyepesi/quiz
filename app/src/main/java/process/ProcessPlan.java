package process;

import java.util.List;
import java.util.Map;

/** Immutable description of work fixed before execution begins. */
public record ProcessPlan(
        String title,
        String description,
        Map<String, String> parameters,
        List<ProcessPlan> subprocesses) {

    public ProcessPlan {
        title = title == null || title.isBlank() ? "Process" : title;
        description = description == null ? "" : description;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        subprocesses = subprocesses == null ? List.of() : List.copyOf(subprocesses);
    }

    public ProcessPlan(String title, String description, Map<String, String> parameters) {
        this(title, description, parameters, List.of());
    }
}
