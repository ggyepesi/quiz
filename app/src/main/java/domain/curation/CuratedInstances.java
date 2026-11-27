package domain.curation;

import domain.runtime.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Materializes accepted source-derived instances into the ordinary dynamic object pool. */
public final class CuratedInstances {
    private CuratedInstances() { }

    /** The pooled curated objects, and each reference that named nothing in the pool. */
    public record Result(List<WikidataDynamicObject> instances, List<String> unresolved) {
        public Result {
            instances = List.copyOf(instances);
            unresolved = List.copyOf(unresolved);
        }
    }

    /** Adds or refreshes the supplied curated instances, resolving references by exact class. */
    public static Result apply(
            List<WikidataDynamicObject> pool,
            Collection<CuratedInstance> instances) {
        return apply(pool, instances, type -> null);
    }

    /**
     * Adds or refreshes the supplied curated instances. A reference to a class resolves to
     * a loaded instance of that class or of one of its subclasses under the domain's
     * {@code baseType} hierarchy: a story's NorseCharacter reference names the loaded
     * NorseDeity, not "nothing loaded".
     */
    public static Result apply(
            List<WikidataDynamicObject> pool,
            Collection<CuratedInstance> instances,
            java.util.function.Function<String, String> baseType) {
        if (pool == null || instances == null || instances.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        TypedInstanceIndex<WikidataDynamicObject> instancesByType =
                TypedInstanceIndex.of(pool);

        List<CuratedInstance> declarations = instances.stream()
                .filter(java.util.Objects::nonNull).toList();
        List<WikidataDynamicObject> materialized = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (CuratedInstance declaration : declarations) {
            WikidataDynamicObject value =
                    instancesByType.get(declaration.type(), declaration.id());
            if (value == null) {
                value = new WikidataDynamicObject(
                        declaration.id(), declaration.displayName());
                value.type(declaration.type());
                pool.add(value);
                instancesByType.add(value);
            } else if (!declaration.displayName().isBlank()) {
                value.name(declaration.displayName());
            }
            materialized.add(value);
        }

        for (int index = 0; index < materialized.size(); index++) {
            CuratedInstance declaration = declarations.get(index);
            WikidataDynamicObject target = materialized.get(index);
            for (CuratedInstance.FieldValue field : declaration.fields()) {
                Object value = field.references().isEmpty()
                        ? field.value()
                        : references(declaration, field, instancesByType, baseType, unresolved);
                if (value != null) target.put(field.field(), value);
            }
        }
        return new Result(materialized, unresolved);
    }

    /**
     * The pooled objects a curated field names. A reference that names nothing stays
     * unresolved and is reported: a stand-in carrying only the QID would be stamped as a
     * class member, indistinguishable from an instance whose acquisition failed.
     */
    private static List<WikidataDynamicObject> references(
            CuratedInstance declaration, CuratedInstance.FieldValue field,
            TypedInstanceIndex<WikidataDynamicObject> index,
            java.util.function.Function<String, String> baseType,
            List<String> unresolved) {
        List<WikidataDynamicObject> result = new ArrayList<>();
        for (CuratedInstance.Reference reference : field.references()) {
            WikidataDynamicObject value =
                    index.getAssignable(reference.type(), reference.id(), baseType);
            if (value == null) {
                unresolved.add(declaration.type() + " " + declaration.id() + "."
                        + field.field() + " → " + reference.type() + " " + reference.id());
                continue;
            }
            if (!result.contains(value)) result.add(value);
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

}
