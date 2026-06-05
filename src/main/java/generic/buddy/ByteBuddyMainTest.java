package generic.buddy;

import java.util.List;

public class ByteBuddyMainTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Initializing Target Population ===");
        List<UserRecord> population = List.of(
                new UserRecord("alpha_admin", 32, true),
                new UserRecord("beta_tester", 15, true),     // Fails age check
                new UserRecord("guest_account", 40, false)  // Fails active check
        );

        // 1. Define the Configuration state metrics dynamically
        ValidationConfig config = new ValidationConfig();
        config.addRule(ValidationConfig.RuleType.CONTAINS, "admin");
        config.addRule(ValidationConfig.RuleType.MIN_AGE, 18);
        config.addRule(ValidationConfig.RuleType.STATE, true);

        // 2. Generate the Controller-Specific Executor via ByteBuddy
        System.out.println("=== Generating Specialized ByteBuddy Bytecode Class ===");
        CompiledValidator fastValidator = ByteBuddyExecutorFactory.generate(config);

        System.out.println("Generated Class Type Name: " + fastValidator.getClass().getName());

        // 3. Run the processing scan pipeline at machine speeds
        System.out.println("\n=== Processing Pipeline Run ===");
        for (UserRecord user : population) {
            boolean result = fastValidator.isValid(user);
            System.out.printf("User: %-15s -> Verification Passed: %b%n", user.username(), result);
        }
    }
}