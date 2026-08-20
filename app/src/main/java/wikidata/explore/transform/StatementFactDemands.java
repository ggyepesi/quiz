package wikidata.explore.transform;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.generation.FactDemand;
import wikidata.explore.generation.GenerationFactDemandPlan;
import wikidata.explore.model.FieldType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Routes downstream class demands backwards through a statement recipe.
 *
 * <p>A statement subject may later occupy a role when its qualifier is absent; a
 * statement value or qualifier QID may become the configured target class of that
 * field. This plan retains each target class's semantic closure at the first point
 * where the corresponding QID population is known.
 */
public record StatementFactDemands(
        List<FactDemand> subjectDemands,
        Map<String, List<FactDemand>> fieldDemands) {

    public static final StatementFactDemands EMPTY =
            new StatementFactDemands(List.of(), Map.of());

    public StatementFactDemands {
        subjectDemands = subjectDemands == null ? List.of() : List.copyOf(subjectDemands);
        Map<String, List<FactDemand>> frozen = new LinkedHashMap<>();
        if (fieldDemands != null) fieldDemands.forEach((field, demands) ->
                frozen.put(field, demands == null ? List.of() : List.copyOf(demands)));
        fieldDemands = Map.copyOf(frozen);
    }

    public List<FactDemand> forField(String fieldName) {
        return fieldDemands.getOrDefault(fieldName, List.of());
    }

    public static StatementFactDemands compile(
            CompiledProjectModel project,
            ModelStatementReifications.Reification recipe,
            GenerationFactDemandPlan demands) {
        if (project == null || recipe == null || demands == null) return EMPTY;
        CompiledClass statementClass = project.findClass(recipe.load().statementType())
                .orElse(null);
        if (statementClass == null) return EMPTY;

        Map<String, CompiledField> fields = new LinkedHashMap<>();
        for (CompiledField field : statementClass.effectiveFields()) {
            if (field != null) fields.put(field.name(), field);
        }

        LinkedHashSet<FactDemand> subject = new LinkedHashSet<>();
        for (ReifyConstruct.Role role : recipe.reify().roles()) {
            if (!role.fallbackToSource()) continue;
            CompiledField field = fields.get(role.field());
            if (field != null) subject.addAll(demands.forClass(field.entityClassName()));
        }

        Map<String, List<FactDemand>> byField = new LinkedHashMap<>();
        for (CompiledField field : fields.values()) {
            if (field.type() != FieldType.ENTITY || field.entityClassName().isBlank()) continue;
            List<FactDemand> target = demands.forClass(field.entityClassName());
            if (!target.isEmpty()) byField.put(field.name(), target);
        }
        return new StatementFactDemands(new ArrayList<>(subject), byField);
    }
}
