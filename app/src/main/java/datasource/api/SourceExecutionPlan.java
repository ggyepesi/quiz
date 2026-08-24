package datasource.api;

import datasource.api.acquisition.SourceAcquisitionOperation;

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
            Mode mode) {
        public Step {
            if (binding == null) throw new IllegalArgumentException("binding is required");
            if (operation == null) throw new IllegalArgumentException("operation is required");
            if (mode == null) throw new IllegalArgumentException("mode is required");
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
            Mode mode = operation instanceof SourceAcquisitionOperation<?>
                    ? Mode.ACQUISITION : Mode.DECLARATION;
            steps.add(new Step(binding, operation, mode));
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
        return steps.size() + " binding(s): " + selfAcquiring()
                + " able to acquire on their own, " + declarations()
                + " declaring data an existing pass retains";
    }
}
