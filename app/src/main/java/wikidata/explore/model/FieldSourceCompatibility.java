package wikidata.explore.model;

import datasource.api.DatasourceOperation;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceExecutionPlan;

import java.util.ArrayList;
import java.util.List;

/** One model-aware check between a datasource operation and the field it fills. */
public final class FieldSourceCompatibility {
    private FieldSourceCompatibility() { }

    public static void requireCompatible(
            GeneratedProjectModel project, SourceExecutionPlan plan) {
        if (plan == null) return;
        for (SourceExecutionPlan.Step step : plan.steps()) {
            requireCompatible(project, step.binding(), step.operation());
        }
    }

    public static void requireCompatible(GeneratedProjectModel project,
            SourceBinding binding, DatasourceOperation operation) {
        if (binding == null || operation == null || !fillsField(binding)) return;
        GeneratedFieldModel field = OwnedFieldSources.declaredField(project,
                binding.target().className(), binding.target().fieldPath());
        if (field == null) {
            throw new IllegalArgumentException("Datasource operation targets unknown field "
                    + displayTarget(binding));
        }
        requireCompatible(binding, operation, field.type(),
                field.cardinality().isCollection());
    }

    /**
     * The same check against a field shape that is not stored yet: an editor applying a
     * new type and a source together checks the source against the type being applied.
     */
    public static void requireCompatible(SourceBinding binding,
            DatasourceOperation operation, datasource.schema.FieldType fieldType,
            boolean fieldCollection) {
        if (binding == null || operation == null || !fillsField(binding)) return;
        String incompatible = operation.outputSchema().incompatibility(
                fieldType, fieldCollection);
        if (!incompatible.isBlank()) {
            throw new IllegalArgumentException("Datasource operation "
                    + binding.recipe().providerId() + "."
                    + binding.recipe().operationId() + " cannot fill "
                    + displayTarget(binding) + ": " + incompatible);
        }
    }

    /**
     * The same check over the stored bindings, reported instead of thrown, so the model's
     * problem list names a mismatch before Generate refuses it. A binding whose operation
     * the registry cannot resolve is left to plan compilation, which names why.
     */
    public static List<FieldSourceBindings.ConfigurationProblem> problems(
            GeneratedProjectModel project, datasource.api.DatasourceRegistry registry) {
        if (project == null || registry == null) return List.of();
        List<FieldSourceBindings.ConfigurationProblem> problems = new ArrayList<>();
        for (SourceBinding binding : FieldSourceBindings.collect(project)) {
            if (binding == null || !fillsField(binding)) continue;
            DatasourceOperation operation;
            try {
                operation = binding.recipe().resolve(registry);
            } catch (IllegalArgumentException | IllegalStateException unresolved) {
                continue;
            }
            try {
                requireCompatible(project, binding, operation);
            } catch (IllegalArgumentException incompatible) {
                problems.add(new FieldSourceBindings.ConfigurationProblem(
                        displayTarget(binding), incompatible.getMessage()));
            }
        }
        return List.copyOf(problems);
    }

    private static boolean fillsField(SourceBinding binding) {
        SourceBindingSlot slot = binding.target().slot();
        return slot == SourceBindingSlot.PRIMARY_FIELD_VALUE
                || slot == SourceBindingSlot.FALLBACK_FIELD_VALUE;
    }

    private static String displayTarget(SourceBinding binding) {
        String target = binding.target().className() + "." + binding.target().fieldPath();
        return binding.target().contextual() ? target + " at "
                + binding.target().contextClassName() + "."
                + binding.target().contextFieldPath() : target;
    }
}
