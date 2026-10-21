package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settles whether pipeline-constructed instances contain modeled payload.
 *
 * <p>The construct that manufactures an instance supplies its payload field names; its
 * identity, display label, provenance and construction links are deliberately absent
 * from that list. This class owns the shared graph rule: constructed references count
 * only when their target also survives, so empty chains and empty cycles cannot keep
 * themselves alive.
 *
 * <p>Source entities do not pass through this rule. Their source identity and class
 * admission are already evidence that the entity exists. Statement records retain
 * their stricter declared-end integrity rule.
 */
public final class ConstructedInstanceContent {

    private ConstructedInstanceContent() { }

    /**
     * Drops candidates with no reachable payload and removes every reference to them.
     *
     * @param payloadFields candidate instance to the configured fields that count as
     *                      its real content
     * @return removed instances, in pool order
     */
    public static List<WikidataDynamicObject> prune(
            Collection<WikidataDynamicObject> pool,
            Map<WikidataDynamicObject, ? extends Collection<String>> payloadFields) {
        if (pool == null || pool.isEmpty()
                || payloadFields == null || payloadFields.isEmpty()) return List.of();

        Set<WikidataDynamicObject> candidates =
                Collections.newSetFromMap(new IdentityHashMap<>());
        candidates.addAll(payloadFields.keySet());

        // Grow from terminal payload. This direction matters: beginning with all
        // candidates and subtracting would make a value-free reference cycle appear to
        // contain itself.
        Set<WikidataDynamicObject> populated =
                Collections.newSetFromMap(new IdentityHashMap<>());
        boolean changed;
        do {
            changed = false;
            for (var candidate : payloadFields.entrySet()) {
                WikidataDynamicObject instance = candidate.getKey();
                if (instance == null || populated.contains(instance)) continue;
                Collection<String> fields = candidate.getValue();
                if (fields == null) continue;
                if (fields.stream().anyMatch(field -> field != null
                        && hasValue(instance.get(field), candidates, populated))) {
                    changed |= populated.add(instance);
                }
            }
        } while (changed);

        Set<WikidataDynamicObject> drop =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (WikidataDynamicObject candidate : candidates) {
            if (candidate != null && !populated.contains(candidate)) drop.add(candidate);
        }
        if (drop.isEmpty()) return List.of();

        // Unlink in the same operation that removes the instances. In particular this
        // clears an owned component's producing field, but the invariant is general:
        // no survivor points at a constructed instance absent from the pool.
        for (WikidataDynamicObject value : pool) {
            if (value == null || drop.contains(value)) continue;
            for (String fieldName : new ArrayList<>(value.dynamicFields().keySet())) {
                Object fieldValue = value.get(fieldName);
                if (fieldValue instanceof WikidataDynamicObject referenced) {
                    if (drop.contains(referenced)) value.remove(fieldName);
                } else if (fieldValue instanceof Collection<?> values) {
                    List<Object> kept = values.stream()
                            .filter(item -> !(item instanceof WikidataDynamicObject ref)
                                    || !drop.contains(ref))
                            .map(item -> (Object) item)
                            .toList();
                    if (kept.size() != values.size()) {
                        if (kept.isEmpty()) value.remove(fieldName);
                        else value.put(fieldName, new ArrayList<>(kept));
                    }
                }
            }
        }

        List<WikidataDynamicObject> removed = new ArrayList<>();
        for (WikidataDynamicObject value : pool) {
            if (drop.contains(value)) removed.add(value);
        }
        pool.removeIf(drop::contains);
        return List.copyOf(removed);
    }

    private static boolean hasValue(
            Object value,
            Set<WikidataDynamicObject> candidates,
            Set<WikidataDynamicObject> populated) {
        if (value == null) return false;
        if (value instanceof CharSequence text) return !text.toString().isBlank();
        if (value instanceof WikidataDynamicObject object) {
            return !candidates.contains(object) || populated.contains(object);
        }
        if (value instanceof Collection<?> values) {
            return values.stream().anyMatch(item ->
                    hasValue(item, candidates, populated));
        }
        if (value instanceof Map<?, ?> values) return !values.isEmpty();
        return true;
    }
}
