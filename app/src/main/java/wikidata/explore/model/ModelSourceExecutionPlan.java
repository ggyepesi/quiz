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
    public static SourceExecutionPlan compile(
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
                + ". Compiled and checked, not executed — the configured field sources"
                + " still drive this run.";
    }
}
