package generic.buddy;

import java.util.Map;

public class ValidationLogicBlueprint {

    // This method contains the exact flat sequence ByteBuddy will use
    // to evaluate a record against a specific rule map without reflection
    public static boolean evaluateRules(UserRecord target, Map<String, Object> rules) {
        if (rules.containsKey("CONTAINS")) {
            String substring = (String) rules.get("CONTAINS");
            if (target.username() == null || !target.username().contains(substring)) {
                return false;
            }
        }
        if (rules.containsKey("MIN_AGE")) {
            int minAge = (Integer) rules.get("MIN_AGE");
            if (target.age() < minAge) {
                return false;
            }
        }
        if (rules.containsKey("STATE")) {
            boolean expectedState = (Boolean) rules.get("STATE");
            if (target.isActive() != expectedState) {
                return false;
            }
        }
        return true;
    }
}