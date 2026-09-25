package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The explicit instance inputs a consuming project takes from imported models.
 *
 * <p>An imported class contributes schema, never its producer's class snapshot and never
 * permission to rerun that producer's population rule.  A {@link PopulationSelection}
 * is the deliberate crossing: when one of this project's own constructs names it, its
 * stable QIDs are materialized as instances of the class named by the population.
 *
 * <p>This is the single discovery path for that crossing.  Merely arriving beside an
 * imported class through {@link ClassImportPlan} does not consume a population; the
 * importing project has to reference the selection from one of its own constructs.
 */
public final class ImportedPopulationInputs {
    private final Map<String, List<String>> qidsByClass;
    private final Map<String, List<String>> selectionsByClass;

    private ImportedPopulationInputs(Map<String, List<String>> qidsByClass,
            Map<String, List<String>> selectionsByClass) {
        this.qidsByClass = Map.copyOf(qidsByClass);
        this.selectionsByClass = Map.copyOf(selectionsByClass);
    }

    public static ImportedPopulationInputs of(GeneratedProjectModel project) {
        if (project == null) return new ImportedPopulationInputs(Map.of(), Map.of());

        Set<String> referencedSelections = new LinkedHashSet<>();
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null || clazz.isImported()) continue;
            addBound(referencedSelections, clazz.membership());
            StatementClassSource statement = clazz.statementSource();
            if (statement != null) {
                addBound(referencedSelections, statement.subjectBound());
                addBound(referencedSelections, statement.objectBound());
            }
            addFieldSelections(project, referencedSelections, clazz.fields());
            GraphClassSource graph = clazz.graphSource();
            if (graph != null && graph.startNode() != null
                    && !graph.startNode().populationSelection().isBlank()) {
                referencedSelections.add(graph.startNode().populationSelection());
            }
        }

        Map<String, LinkedHashSet<String>> qids = new LinkedHashMap<>();
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (String name : referencedSelections) {
            Selection selection = project.findSelection(name);
            if (!(selection instanceof PopulationSelection population)) continue;
            GeneratedClassModel clazz = project.findClass(population.className());
            if (clazz == null || !clazz.isImported()) continue;
            qids.computeIfAbsent(clazz.className(), ignored -> new LinkedHashSet<>())
                    .addAll(population.instanceQids());
            names.computeIfAbsent(clazz.className(), ignored -> new ArrayList<>())
                    .add(population.name());
        }

        Map<String, List<String>> frozenQids = new LinkedHashMap<>();
        qids.forEach((className, values) ->
                frozenQids.put(className, List.copyOf(values)));
        Map<String, List<String>> frozenNames = new LinkedHashMap<>();
        names.forEach((className, values) ->
                frozenNames.put(className, List.copyOf(values)));
        return new ImportedPopulationInputs(frozenQids, frozenNames);
    }

    public List<String> qidsFor(String className) {
        return qidsByClass.getOrDefault(clean(className), List.of());
    }

    public List<String> selectionNamesFor(String className) {
        return selectionsByClass.getOrDefault(clean(className), List.of());
    }

    private static void addBound(Set<String> names, EntityBound bound) {
        if (bound != null && bound.kind() == EntityBound.Kind.VOCABULARY
                && !bound.selectionName().isBlank()) {
            names.add(bound.selectionName());
        }
    }

    private static void addFieldSelections(GeneratedProjectModel project,
            Set<String> names, List<GeneratedFieldModel> fields) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            if (project.findClass(field.entityClassName()) == null
                    && project.findSelection(field.entityClassName()) != null) {
                names.add(field.entityClassName());
            }
            addFieldSelections(project, names, field.fields());
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
