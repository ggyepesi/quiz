package quiz.transform.app;

import quiz.Quizable;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The schema of a loaded snapshot, derived from the instances (no model needed):
 * the type names present and, per type, its field names and which are references
 * (entity-valued). Feeds the view config UI's dropdowns.
 */
public final class DomainSchema {

    private final Map<String, Set<String>> fieldsByType = new LinkedHashMap<>();
    private final Set<String> referenceFields = new LinkedHashSet<>();
    private final Set<String> collectionFields = new LinkedHashSet<>();

    public DomainSchema(Collection<WikidataDynamicObject> pool) {
        for (WikidataDynamicObject o : pool) {
            if (o == null || o.typeName() == null || o.typeName().isBlank()) {
                continue;
            }
            Set<String> fields = fieldsByType.computeIfAbsent(
                    o.typeName(), k -> new LinkedHashSet<>());
            for (Map.Entry<String, Object> e : o.dynamicFieldValues().entrySet()) {
                fields.add(e.getKey());
                String key = o.typeName() + "." + e.getKey();
                if (isReference(e.getValue())) {
                    referenceFields.add(key);
                }
                if (e.getValue() instanceof Collection<?>) {
                    collectionFields.add(key);
                }
            }
        }
    }

    public boolean isCollection(String type, String field) {
        return collectionFields.contains(type + "." + field);
    }

    public List<String> types() {
        return new ArrayList<>(fieldsByType.keySet());
    }

    public List<String> fields(String type) {
        return new ArrayList<>(fieldsByType.getOrDefault(type, Set.of()));
    }

    public boolean isReference(String type, String field) {
        return referenceFields.contains(type + "." + field);
    }

    private static boolean isReference(Object v) {
        if (v instanceof Quizable) {
            return true;
        }
        if (v instanceof Collection<?> c) {
            for (Object i : c) {
                if (i instanceof Quizable) {
                    return true;
                }
            }
        }
        return false;
    }
}
