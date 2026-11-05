package quiz.curation;

import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Adds or refreshes the supplied curated instances. */
    public static Result apply(
            List<WikidataDynamicObject> pool,
            Collection<CuratedInstance> instances) {
        if (pool == null || instances == null || instances.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        Map<Key, WikidataDynamicObject> byKey = new LinkedHashMap<>();
        Map<String, List<WikidataDynamicObject>> byId = new LinkedHashMap<>();
        for (WikidataDynamicObject value : pool) index(value, byKey, byId);

        List<CuratedInstance> declarations = instances.stream()
                .filter(java.util.Objects::nonNull).toList();
        List<WikidataDynamicObject> materialized = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (CuratedInstance declaration : declarations) {
            Key key = new Key(declaration.type(), declaration.id());
            WikidataDynamicObject value = byKey.get(key);
            if (value == null) {
                value = new WikidataDynamicObject(
                        declaration.id(), declaration.displayName());
                value.type(declaration.type());
                pool.add(value);
                index(value, byKey, byId);
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
                        : references(declaration, field, byKey, byId, unresolved);
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
            Map<Key, WikidataDynamicObject> byKey,
            Map<String, List<WikidataDynamicObject>> byId,
            List<String> unresolved) {
        List<WikidataDynamicObject> result = new ArrayList<>();
        for (CuratedInstance.Reference reference : field.references()) {
            WikidataDynamicObject value = byKey.get(new Key(reference.type(), reference.id()));
            if (value == null) {
                value = byId.getOrDefault(reference.id(), List.of()).stream()
                        .filter(candidate -> candidate.directClassNames()
                                .contains(reference.type()))
                        .findFirst().orElse(null);
            }
            if (value == null) {
                unresolved.add(declaration.type() + " " + declaration.id() + "."
                        + field.field() + " → " + reference.type() + " " + reference.id());
                continue;
            }
            if (!result.contains(value)) result.add(value);
        }
        return result.isEmpty() ? null : List.copyOf(result);
    }

    private static void index(WikidataDynamicObject value,
            Map<Key, WikidataDynamicObject> byKey,
            Map<String, List<WikidataDynamicObject>> byId) {
        if (value == null || value.getIdentifier() == null) return;
        for (String type : value.directClassNames()) {
            byKey.putIfAbsent(new Key(type, value.getIdentifier()), value);
        }
        byId.computeIfAbsent(value.getIdentifier(), ignored -> new ArrayList<>()).add(value);
    }

    private record Key(String type, String id) { }
}
