package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every checked-in model is exercised by the validator that gates generation.
 * A new rule must therefore either migrate its shipped models in the same change or
 * add a narrow, reasoned allowance here; it cannot wait for somebody to regenerate a
 * domain days later. Warnings deliberately remain visible without failing this guard.
 */
class EveryShippedModelValidatesTest {

    private record Allowance(String model, String rule, String reason) {}

    /** Deliberately invalid shipped models only; every entry must still match. */
    private static final List<Allowance> ALLOWANCES = List.of();

    @Test
    void everyShippedModelHasNoUnacknowledgedValidationErrors() throws Exception {
        Path root = Path.of("../data/wikidata");
        List<String> unexpected = new ArrayList<>();
        List<Allowance> unused = new ArrayList<>(ALLOWANCES);
        int count = 0;

        assertTrue(ALLOWANCES.stream().allMatch(allowance ->
                        !allowance.model().isBlank()
                                && !allowance.rule().isBlank()
                                && !allowance.reason().isBlank()),
                "every validation allowance must name its model, rule and reason");

        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString()
                            .endsWith(".model.json"))
                    .sorted().toList()) {
                count++;
                String model = root.relativize(path).toString();
                GeneratedProjectModel project =
                        new GeneratedProjectModelStore().load(path.toFile());
                for (GeneratedProjectModelValidator.Problem error
                        : GeneratedProjectModelValidator.validate(project).errors()) {
                    Allowance matched = unused.stream()
                            .filter(allowance -> allowance.model().equals(model)
                                    && error.message().contains(allowance.rule()))
                            .findFirst().orElse(null);
                    if (matched == null) {
                        unexpected.add(model + " — " + error);
                    } else {
                        unused.remove(matched);
                    }
                }
            }
        }

        assertFalse(count == 0, "no shipped model files were found under " + root);
        assertEquals(List.of(), unexpected,
                "A validation rule and the shipped models it governs must land together");
        assertEquals(List.of(), unused,
                "Remove allowances whose deliberately invalid model is now repaired");
    }
}
