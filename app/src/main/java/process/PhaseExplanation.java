package process;

import java.util.List;

/**
 * Structured explanation of one executable phase.
 *
 * <p>This is run-independent configuration: the same object renders before execution,
 * during a live run, and after restoration from a saved-run artifact. Runtime status,
 * timing and summaries remain on {@link ProcessWorkflowPipeline.PhaseState}.
 */
public record PhaseExplanation(
        String purpose,
        List<String> inputs,
        List<String> operations,
        List<String> outputs,
        List<ModelReference> references,
        List<PhaseExample> examples) {

    public static final PhaseExplanation EMPTY = new PhaseExplanation(
            "", List.of(), List.of(), List.of(), List.of(), List.of());

    public PhaseExplanation {
        purpose = clean(purpose);
        inputs = copy(inputs);
        operations = copy(operations);
        outputs = copy(outputs);
        references = references == null ? List.of() : List.copyOf(references);
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return purpose.isBlank() && inputs.isEmpty() && operations.isEmpty()
                && outputs.isEmpty() && references.isEmpty() && examples.isEmpty();
    }

    public enum ReferenceKind { CLASS, FIELD, PROPERTY, KIND_RULE, ROLE, PHASE }

    /** A navigable reference into model, source-property, or pipeline configuration. */
    public record ModelReference(
            ReferenceKind kind, String owner, String name, String label) {
        public ModelReference {
            kind = kind == null ? ReferenceKind.PHASE : kind;
            owner = clean(owner);
            name = clean(name);
            label = clean(label);
            if (label.isBlank()) label = owner.isBlank() ? name : owner + "." + name;
        }

        public static ModelReference clazz(String className) {
            return new ModelReference(ReferenceKind.CLASS, "", className, className);
        }

        public static ModelReference field(String owner, String field) {
            return new ModelReference(
                    ReferenceKind.FIELD, owner, field, owner + "." + field);
        }

        public static ModelReference property(String pid) {
            return new ModelReference(ReferenceKind.PROPERTY, "", pid, pid);
        }

        public static ModelReference kindRule(String className) {
            return new ModelReference(
                    ReferenceKind.KIND_RULE, className, "", className + " kind rule");
        }
    }

    public enum ExampleKind { PLANNED, CHANGED, SKIPPED, UNRESOLVED }

    /** A bounded, human-scale illustration of inputs, evidence and resulting change. */
    public record PhaseExample(
            ExampleKind kind,
            String title,
            List<String> input,
            List<String> evidence,
            List<String> output,
            List<ModelReference> references) {
        public PhaseExample {
            kind = kind == null ? ExampleKind.PLANNED : kind;
            title = clean(title);
            input = copy(input);
            evidence = copy(evidence);
            output = copy(output);
            references = references == null ? List.of() : List.copyOf(references);
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(PhaseExplanation::clean).filter(v -> !v.isBlank()).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
