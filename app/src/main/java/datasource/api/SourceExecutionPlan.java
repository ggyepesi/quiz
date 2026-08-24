package datasource.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, resolved datasource work declared by one domain model.
 *
 * <p>A recipe is durable configuration; an operation is an installed capability. This
 * is the boundary between them. Compilation resolves every recipe once, rejects two
 * recipes occupying the same target, and records whether the capability performs a
 * standalone acquisition or declares data retained by the surrounding pipeline.
 *
 * <p>The plan deliberately does not execute anything. Population, field and evidence
 * consumers have different inputs and result types; they consume the relevant steps at
 * their existing batching/cache/checkpoint boundary rather than growing a second runner.
 */
public final class SourceExecutionPlan {

    public enum Mode {
        /** The operation supplies a Query and can perform its own acquisition. */
        ACQUISITION,
        /** The operation configures values retained by an existing acquisition pass. */
        DECLARATION
    }

    public record Step(
            SourceBinding binding,
            DatasourceOperation operation,
            Mode mode,
            PreparedSourceOperation prepared) {
        public Step(SourceBinding binding, DatasourceOperation operation, Mode mode) {
            this(binding, operation, mode, operation.prepare(binding));
        }
        public Step {
            if (binding == null) throw new IllegalArgumentException("binding is required");
            if (operation == null) throw new IllegalArgumentException("operation is required");
            if (mode == null) throw new IllegalArgumentException("mode is required");
            if (prepared == null) throw new IllegalArgumentException("prepared operation is required");
        }

        public SourceBindingTarget target() { return binding.target(); }
        public SourceRecipe recipe() { return binding.recipe(); }
    }

    private final List<Step> steps;
    private final Map<SourceBindingTarget, Step> byTarget;

    private SourceExecutionPlan(List<Step> steps) {
        this.steps = List.copyOf(steps);
        LinkedHashMap<SourceBindingTarget, Step> index = new LinkedHashMap<>();
        for (Step step : steps) index.put(step.target(), step);
        this.byTarget = Map.copyOf(index);
    }

    public static SourceExecutionPlan compile(
            Collection<SourceBinding> bindings, DatasourceRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("Datasource registry is required");
        List<Step> steps = new ArrayList<>();
        LinkedHashMap<SourceBindingTarget, SourceBinding> occupied = new LinkedHashMap<>();
        if (bindings != null) for (SourceBinding binding : bindings) {
            if (binding == null) continue;
            SourceBinding previous = occupied.putIfAbsent(binding.target(), binding);
            if (previous != null) {
                throw new IllegalArgumentException("Two datasource recipes occupy "
                        + binding.target().className()
                        + (binding.target().fieldPath().isBlank() ? ""
                                : "." + binding.target().fieldPath())
                        + " [" + binding.target().slot().id() + "]: "
                        + previous.recipe().providerId() + "."
                        + previous.recipe().operationId() + " and "
                        + binding.recipe().providerId() + "."
                        + binding.recipe().operationId());
            }
            DatasourceOperation operation = binding.resolve(registry);
            if (binding.target().scope() == BindingScope.FIELD_VALUE
                    && !operation.outputSchema().kind().bindableToField()) {
                throw new IllegalArgumentException("Datasource operation "
                        + binding.recipe().providerId() + "."
                        + binding.recipe().operationId()
                        + " does not produce a field value");
            }
            PreparedSourceOperation prepared = operation.prepare(binding);
            Mode mode = prepared.execution() == PreparedSourceOperation.Execution.ACQUIRE
                    ? Mode.ACQUISITION : Mode.DECLARATION;
            steps.add(new Step(binding, operation, mode, prepared));
        }
        return new SourceExecutionPlan(steps);
    }

    public List<Step> steps() { return steps; }

    public List<Step> steps(BindingScope scope) {
        if (scope == null) return List.of();
        return steps.stream().filter(step -> step.target().scope() == scope).toList();
    }

    public Step step(SourceBindingTarget target) {
        return target == null ? null : byTarget.get(target);
    }

    /** How many steps COULD acquire on their own — a capability of the operation, not
     *  work performed. Nothing here runs anything. */
    public long selfAcquiring() {
        return steps.stream().filter(step -> step.mode() == Mode.ACQUISITION).count();
    }

    public long declarations() { return steps.size() - selfAcquiring(); }

    public String summary() {
        Map<String, Long> families = steps.stream().collect(java.util.stream.Collectors
                .groupingBy(step -> step.prepared().familyName(), LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        String inventory = families.entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(java.util.stream.Collectors.joining(", "));
        return steps.size() + " binding(s)" + (inventory.isBlank() ? "" : ": " + inventory);
    }

    /**
     * Whether this plan has work for a family — a step it prepared as ACQUIRE.
     *
     * <p>One question with one answer. Each family used to bring its own: hasBindings,
     * configured, and a local scan, all computing "is there anything of mine to do"
     * from the same steps by different routes. A family that prepares an incomplete
     * recipe as RETAIN is already excluded, so this needs no per-family grammar.
     */
    public boolean acquires(String familyId) {
        return steps.stream()
                .filter(step -> step.prepared().familyId().equals(familyId))
                .anyMatch(step -> step.prepared().execution()
                        == PreparedSourceOperation.Execution.ACQUIRE);
    }

    public long familyCount(String familyId) {
        return steps.stream().filter(step -> step.prepared().familyId().equals(familyId)).count();
    }
}
