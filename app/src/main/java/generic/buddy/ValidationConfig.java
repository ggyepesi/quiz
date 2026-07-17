package generic.buddy;

import java.util.HashMap;
import java.util.Map;

public class ValidationConfig {
    public enum RuleType { CONTAINS, MIN_AGE, STATE }

    private final Map<String, Object> activeRules = new HashMap<>();

    public void addRule(RuleType type, Object value) {
        activeRules.put(type.name(), value);
    }

    public Map<String, Object> getActiveRules() {
        return activeRules;
    }
}