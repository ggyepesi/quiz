package wikidata.explore.model;

import datasource.api.DatasourceRegistry;
import datasource.api.SourceBinding;
import datasource.api.SourceExecutionPlan;

import java.util.ArrayList;
import java.util.List;

/** Compiles every class and field source binding in a model into one resolved plan. */
public final class ModelSourceExecutionPlan {
    private ModelSourceExecutionPlan() { }

    /**
     * Banks edits still made through legacy controls, verifies where every binding is
     * stored, then resolves the complete model through the supplied datasource registry.
     */
    public static SourceExecutionPlan synchronizeAndCompile(
            GeneratedProjectModel project, DatasourceRegistry registry) {
        // Banked in this order because the field collector flushes pending editor
        // state first, and a binding edited a moment ago must be in the plan. Listed
        // in the other order, because a class's own sources precede its fields'.
        List<SourceBinding> fields = FieldSourceBindings.synchronizeAndCollect(project);
        List<SourceBinding> bindings =
                new ArrayList<>(ClassSourceBindings.synchronizeAndCollect(project));
        bindings.addAll(fields);
        return SourceExecutionPlan.compile(bindings, registry);
    }

    /** Resolves bindings that have already been banked, without changing the model. */
    public static SourceExecutionPlan compileStored(
            GeneratedProjectModel project, DatasourceRegistry registry) {
        List<SourceBinding> bindings = new ArrayList<>(ClassSourceBindings.collect(project));
        bindings.addAll(FieldSourceBindings.collect(project));
        return SourceExecutionPlan.compile(bindings, registry);
    }

    /** Compatibility boundary for callers not yet explicit about banking edits. */
    @Deprecated(forRemoval = false)
    public static SourceExecutionPlan compile(
            GeneratedProjectModel project, DatasourceRegistry registry) {
        return synchronizeAndCompile(project, registry);
    }

    /**
     * What to say about the plan at the head of a run.
     *
     * <p>Says plainly that nothing runs from it. The counts describe what the bindings
     * ARE — how many could acquire on their own — and on a domain whose bindings all
     * declare retained data that reads as "0 able to acquire" directly above a few
     * hundred entity requests. Without the last clause the only available conclusions
     * are that the plan is broken or that the run ignores it; the second is true, and
     * it should not be left to be inferred.
     */
    public static String message(SourceExecutionPlan plan) {
        return "Datasource plan: " + plan.summary()
                + ". Compiled and checked; execution remains at each prepared family’s"
                + " batching and cache boundary.";
    }

    /** Generate consumes population steps; later source families still ride their
     * established acquisition passes until their own migration milestone. */
    public static String generationMessage(SourceExecutionPlan plan) {
        long populations = plan.steps(datasource.api.BindingScope.CLASS_POPULATION).size();
        return "Datasource plan: " + plan.summary() + ". " + populations
                + " class population binding(s) drive discovery; " + acquiringFields(plan)
                + " drive field acquisition across all configured"
                + " classes; " + wikidataNameClasses(plan)
                + " Wikidata class-name declaration(s) drive label/alias retention;"
                + " remaining field sources still use the established acquisition passes.";
    }

    /** Enrich re-reads a saved graph, so no population is discovered; external
     *  field/evidence bindings are the part of the plan it executes. */
    public static String enrichMessage(SourceExecutionPlan plan) {
        return "Datasource plan: " + plan.summary() + ". " + acquiringFields(plan)
                + " drive field acquisition;"
                + " populations are not re-discovered; " + wikidataNameClasses(plan)
                + " Wikidata class-name declaration(s) drive label/alias retention;"
                + " remaining field sources still use the established acquisition passes.";
    }

    /** Remap performs no source acquisition; it only validates that the persisted
     * recipes still resolve before transforming the saved graph. */
    public static String remapMessage(SourceExecutionPlan plan) {
        return "Datasource plan: " + plan.summary()
                + ". Compiled and checked, not executed — Remap transforms the saved"
                + " graph without acquiring class populations, names or field values.";
    }

    private static String acquiringFields(SourceExecutionPlan plan) {
        java.util.Map<String, Long> families = plan.steps(datasource.api.BindingScope.FIELD_VALUE)
                .stream().filter(step -> step.prepared().execution()
                        == datasource.api.PreparedSourceOperation.Execution.ACQUIRE)
                .collect(java.util.stream.Collectors.groupingBy(
                        step -> step.prepared().familyName(), java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        if (families.isEmpty()) return "0 field acquisition binding(s)";
        return families.entrySet().stream().map(entry -> entry.getValue() + " "
                        + entry.getKey() + " binding(s)")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static long wikidataNameClasses(SourceExecutionPlan plan) {
        java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>(
                ClassNameSourcePlan.labels(plan));
        classes.addAll(ClassNameSourcePlan.aliases(plan));
        return classes.size();
    }
}
