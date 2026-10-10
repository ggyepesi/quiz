package wikidata.explore.transform;

import objectview.field.FieldAccess;
import objectview.field.FieldPath;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Fills collection fields declared as {@link FieldProductionKind#UNION} from
 * values already present elsewhere on the same object graph. No datasource is
 * consulted: the configured field paths are read in order and duplicate values
 * retain their first occurrence. A union may read another union; passes repeat until
 * none changes, so declaration order never decides the result.
 */
public final class ModelFieldUnions {

    private ModelFieldUnions() {}

    public record FieldUnion(String className, String targetField,
                             List<String> sourcePaths) {
        public FieldUnion {
            className = clean(className);
            targetField = clean(targetField);
            sourcePaths = sourcePaths == null ? List.of()
                    : sourcePaths.stream().map(ModelFieldUnions::clean)
                            .filter(path -> !path.isBlank()).distinct().toList();
        }
    }

    public static int apply(GeneratedProjectModel project,
                            Collection<WikidataDynamicObject> pool,
                            GenerationLog log,
                            List<WikidataDynamicObject> changedOut) {
        return apply(derive(project), pool, log, changedOut);
    }

    public static int apply(CompiledProjectModel project,
                            Collection<WikidataDynamicObject> pool,
                            GenerationLog log,
                            List<WikidataDynamicObject> changedOut) {
        return apply(derive(project), pool, log, changedOut);
    }

    private static int apply(List<FieldUnion> unions,
                             Collection<WikidataDynamicObject> pool,
                             GenerationLog log,
                             List<WikidataDynamicObject> changedOut) {
        // A union may read another union's field (allBodies = planets + allMoons), in
        // any declaration order, so one pass can read a field that is filled later in
        // that same pass (#380). Each pass recomputes every union from its sources, so
        // repeating passes until none changes anything settles them all; a replay over
        // already-settled data stops after its first pass. The bound only guards a
        // cyclic declaration whose order keeps moving, and says so.
        Map<WikidataDynamicObject, java.util.Set<String>> changedFields =
                new java.util.IdentityHashMap<>();
        int[] perUnion = new int[unions.size()];
        boolean settled = false;
        for (int pass = 0; pass <= unions.size() && !settled; pass++) {
            settled = true;
            for (int index = 0; index < unions.size(); index++) {
                FieldUnion union = unions.get(index);
                for (WikidataDynamicObject instance : pool) {
                    if (instance == null
                            || !instance.directClassNames().contains(union.className())) {
                        continue;
                    }
                    LinkedHashSet<Object> values = new LinkedHashSet<>();
                    for (String sourcePath : union.sourcePaths()) {
                        flatten(FieldAccess.getPathValues(
                                instance, FieldPath.parse(sourcePath)), values);
                    }
                    List<Object> result = new ArrayList<>(values);
                    if (sameValues(instance.get(union.targetField()), result)) continue;
                    instance.put(union.targetField(), result);
                    settled = false;
                    if (changedFields.computeIfAbsent(instance, ignored ->
                            new java.util.HashSet<>()).add(union.targetField())) {
                        perUnion[index]++;
                    }
                    if (changedOut != null && !changedOut.contains(instance)) {
                        changedOut.add(instance);
                    }
                }
            }
        }
        if (log != null) {
            for (int index = 0; index < unions.size(); index++) {
                FieldUnion union = unions.get(index);
                log.message("Union " + union.className() + "." + union.targetField()
                        + " <- " + union.sourcePaths() + ": " + perUnion[index]
                        + " changed\n");
            }
            if (!settled) {
                log.message("Unions did not settle after " + (unions.size() + 1)
                        + " passes: their declarations read each other in a cycle\n");
            }
        }
        int changed = 0;
        for (int count : perUnion) changed += count;
        return changed;
    }

    private static boolean sameValues(Object current, List<Object> result) {
        if (current instanceof Collection<?> collection) {
            return new ArrayList<>(collection).equals(result);
        }
        return current == null && result.isEmpty();
    }

    private static void flatten(Object value, LinkedHashSet<Object> values) {
        if (value == null) return;
        if (value instanceof Collection<?> collection) {
            collection.forEach(element -> flatten(element, values));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(element -> flatten(element, values));
        } else if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                flatten(Array.get(value, index), values);
            }
        } else {
            values.add(value);
        }
    }

    public static List<FieldUnion> derive(GeneratedProjectModel project) {
        List<FieldUnion> result = new ArrayList<>();
        if (project == null) return result;
        for (GeneratedClassModel owner : project.classes()) {
            for (GeneratedFieldModel field : owner.effectiveFields(project)) {
                if (field != null && field.mapping().productionKind()
                        == FieldProductionKind.UNION) {
                    result.add(new FieldUnion(owner.className(), field.name(),
                            field.mapping().unionSourcePaths()));
                }
            }
        }
        return result;
    }

    public static List<FieldUnion> derive(CompiledProjectModel project) {
        List<FieldUnion> result = new ArrayList<>();
        if (project == null) return result;
        for (CompiledClass owner : project.classes()) {
            for (CompiledField field : owner.effectiveFields()) {
                if (field.source().productionKind() == FieldProductionKind.UNION) {
                    result.add(new FieldUnion(owner.className(), field.name(),
                            field.source().unionSourcePaths()));
                }
            }
        }
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
