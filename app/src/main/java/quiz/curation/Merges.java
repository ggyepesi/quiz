package quiz.curation;

import objectview.ViewableAdapter;
import objectview.field.FieldAccess;
import objectview.field.FieldRef;
import objectview.field.FieldSet;
import quiz.Quizable;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies durable, type-qualified entity merges to a loaded object graph. */
public final class Merges {

    private Merges() {}

    private record Key(String type, String id) {}
    private record Resolved(Merge merge, Key primary, Key duplicate,
                            Quizable primaryObject, Quizable duplicateObject, int depth) {}

    /**
     * Applies all valid directives as one graph operation. Children in a merge chain are
     * folded first, all references are redirected to the final survivor, and only then
     * are duplicate roots removed. Cycles and ambiguous legacy identifiers are rejected.
     */
    public static int apply(Collection<? extends Quizable> pool, List<Merge> merges) {
        if (pool == null || merges == null || merges.isEmpty()) {
            return 0;
        }

        Map<Key, Quizable> typed = new LinkedHashMap<>();
        Map<String, List<Quizable>> byId = new LinkedHashMap<>();
        for (Quizable q : pool) {
            if (q == null || blank(q.getIdentifier())) {
                continue;
            }
            typed.putIfAbsent(new Key(q.typeName(), q.getIdentifier()), q);
            byId.computeIfAbsent(q.getIdentifier(), ignored -> new ArrayList<>()).add(q);
        }

        Map<Key, Key> direct = new LinkedHashMap<>();
        Map<Key, Merge> mergeByDuplicate = new LinkedHashMap<>();
        for (Merge merge : merges) {
            if (merge == null || blank(merge.primary()) || blank(merge.duplicate())
                    || merge.primary().equals(merge.duplicate())) {
                continue;
            }
            Key primary = resolveKey(merge.type(), merge.primary(), typed, byId);
            Key duplicate = resolveKey(merge.type(), merge.duplicate(), typed, byId);
            if (primary == null || duplicate == null || primary.equals(duplicate)) {
                continue;
            }
            Key previous = direct.putIfAbsent(duplicate, primary);
            if (previous != null && !previous.equals(primary)) {
                throw new IllegalArgumentException(
                        "Multiple merge targets for " + duplicate + ": "
                                + previous + " and " + primary);
            }
            mergeByDuplicate.put(duplicate, merge);
        }

        // Resolve first: this detects cycles before any object is mutated.
        Map<Key, Key> finalTarget = new LinkedHashMap<>();
        for (Key duplicate : direct.keySet()) {
            finalTarget.put(duplicate, finalTarget(
                    duplicate, direct, new LinkedHashSet<>(), finalTarget));
        }

        List<Resolved> work = new ArrayList<>();
        for (Map.Entry<Key, Key> edge : direct.entrySet()) {
            Key duplicate = edge.getKey();
            Key immediatePrimary = edge.getValue();
            Quizable duplicateObject = typed.get(duplicate);
            Quizable primaryObject = typed.get(immediatePrimary);
            if (duplicateObject != null && primaryObject != null) {
                work.add(new Resolved(
                        mergeByDuplicate.get(duplicate),
                        immediatePrimary,
                        duplicate,
                        primaryObject,
                        duplicateObject,
                        depth(duplicate, direct)));
            }
        }

        // C -> B must happen before B -> A.
        work.sort(Comparator.comparingInt(Resolved::depth).reversed());

        Map<Quizable, Quizable> replacements = new IdentityHashMap<>();
        int applied = 0;
        for (Resolved resolved : work) {
            Quizable primary = replacementOf(resolved.primaryObject(), replacements);
            Quizable duplicate = replacementOf(resolved.duplicateObject(), replacements);
            if (primary == duplicate) {
                continue;
            }
            union(primary, duplicate, resolved.merge());
            replacements.put(duplicate, primary);
            applied++;
        }

        if (applied == 0) {
            return 0;
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Quizable root : new ArrayList<>(pool)) {
            rewriteFields(replacementOf(root, replacements), replacements, visited);
        }
        pool.removeIf(q -> q != null && replacements.containsKey(q));
        return applied;
    }

    private static Key resolveKey(
            String type, String id, Map<Key, Quizable> typed,
            Map<String, List<Quizable>> byId) {
        if (!blank(type)) {
            Key key = new Key(type, id);
            return typed.containsKey(key) ? key : null;
        }
        // Backward compatibility for old sidecars: accept an untyped id only when it
        // resolves uniquely. Ambiguity is unsafe, so leave the directive unapplied.
        List<Quizable> matches = byId.getOrDefault(id, List.of());
        if (matches.size() != 1) {
            return null;
        }
        Quizable q = matches.get(0);
        return new Key(q.typeName(), q.getIdentifier());
    }

    private static Key finalTarget(
            Key key, Map<Key, Key> direct, Set<Key> path, Map<Key, Key> memo) {
        Key cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        Key next = direct.get(key);
        if (next == null) {
            return key;
        }
        if (!path.add(key)) {
            throw new IllegalArgumentException("Merge cycle involving " + key);
        }
        Key result = finalTarget(next, direct, path, memo);
        path.remove(key);
        memo.put(key, result);
        return result;
    }

    private static int depth(Key key, Map<Key, Key> direct) {
        int depth = 0;
        Set<Key> seen = new LinkedHashSet<>();
        while (direct.containsKey(key) && seen.add(key)) {
            key = direct.get(key);
            depth++;
        }
        return depth;
    }

    private static Quizable replacementOf(
            Quizable value, Map<Quizable, Quizable> replacements) {
        Quizable current = value;
        Set<Quizable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && replacements.containsKey(current) && seen.add(current)) {
            current = replacements.get(current);
        }
        return current;
    }

    private static void union(Quizable primary, Quizable duplicate, Merge merge) {
        for (FieldRef ref : FieldSet.of(duplicate).fields()) {
            String name = ref.name();
            Object duplicateValue = FieldAccess.getPath(duplicate, name);
            Object primaryValue = FieldAccess.getPath(primary, name);
            String source = merge.sourceFor(name);

            if (Merge.PRIMARY.equals(source)) {
                continue;
            }
            if (Merge.DUPLICATE.equals(source)) {
                if (ViewableAdapter.isValidQuizValue(duplicateValue)) {
                    FieldAccess.setPath(primary, name,
                            copyLike(primaryValue, duplicateValue));
                }
                continue;
            }
            if (Merge.BOTH.equals(source)) {
                FieldAccess.setPath(primary, name, unionValue(primaryValue, duplicateValue));
                continue;
            }

            if (!ViewableAdapter.isValidQuizValue(duplicateValue)) {
                continue;
            }
            if (!ViewableAdapter.isValidQuizValue(primaryValue)) {
                FieldAccess.setPath(primary, name,
                        copyLike(primaryValue, duplicateValue));
            } else if (bothCollections(primaryValue, duplicateValue)
                    || bothMaps(primaryValue, duplicateValue)) {
                FieldAccess.setPath(primary, name, unionValue(primaryValue, duplicateValue));
            }
        }
    }

    public static Object unionValue(Object primary, Object duplicate) {
        if (primary instanceof Collection<?> pc && duplicate instanceof Collection<?> dc) {
            Collection<Object> result = newCollectionLike(primary, duplicate);
            addDistinct(result, pc);
            addDistinct(result, dc);
            return result;
        }
        if (primary instanceof Map<?, ?> pm && duplicate instanceof Map<?, ?> dm) {
            Map<Object, Object> result = newMapLike(primary, duplicate);
            pm.forEach(result::put);
            dm.forEach(result::putIfAbsent);
            return result;
        }
        return ViewableAdapter.isValidQuizValue(primary) ? primary : duplicate;
    }

    private static Object copyLike(Object preferredShape, Object value) {
        if (value instanceof Collection<?> collection) {
            Collection<Object> result = newCollectionLike(preferredShape, value);
            addDistinct(result, collection);
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = newMapLike(preferredShape, value);
            map.forEach(result::put);
            return result;
        }
        return value;
    }

    private static void rewriteFields(
            Quizable object, Map<Quizable, Quizable> replacements, Set<Object> visited) {
        if (object == null || !visited.add(object)) {
            return;
        }
        FieldSet fields = FieldSet.of(object);
        for (FieldRef ref : fields.fields()) {
            Object oldValue = fields.read(ref.name());
            Object newValue = rewriteValue(oldValue, replacements, visited);
            if (newValue != oldValue) {
                fields.write(ref.name(), newValue);
            }
        }
    }

    private static Object rewriteValue(
            Object value, Map<Quizable, Quizable> replacements, Set<Object> visited) {
        if (value instanceof Quizable q) {
            Quizable replacement = replacementOf(q, replacements);
            rewriteFields(replacement, replacements, visited);
            return replacement;
        }
        if (value instanceof Collection<?> collection) {
            Collection<Object> result = newCollectionLike(value, value);
            boolean changed = false;
            for (Object item : collection) {
                Object rewritten = rewriteValue(item, replacements, visited);
                changed |= rewritten != item;
                if (!result.contains(rewritten)) {
                    result.add(rewritten);
                } else {
                    changed = true;
                }
            }
            return changed ? result : value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = newMapLike(value, value);
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = rewriteValue(entry.getKey(), replacements, visited);
                Object mapped = rewriteValue(entry.getValue(), replacements, visited);
                changed |= key != entry.getKey() || mapped != entry.getValue();
                result.putIfAbsent(key, mapped);
            }
            return changed ? result : value;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollectionLike(Object preferred, Object fallback) {
        Object sample = preferred instanceof Collection<?> ? preferred : fallback;
        Collection<Object> reflected = construct(sample, Collection.class);
        if (reflected != null) {
            return reflected;
        }
        return sample instanceof Set<?> ? new LinkedHashSet<>() : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMapLike(Object preferred, Object fallback) {
        Object sample = preferred instanceof Map<?, ?> ? preferred : fallback;
        Map<Object, Object> reflected = construct(sample, Map.class);
        return reflected != null ? reflected : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static <T> T construct(Object sample, Class<T> expected) {
        if (sample == null) {
            return null;
        }
        try {
            Constructor<?> constructor = sample.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            Object value = constructor.newInstance();
            return expected.isInstance(value) ? (T) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void addDistinct(Collection<Object> target, Collection<?> values) {
        for (Object value : values) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    private static boolean bothCollections(Object a, Object b) {
        return a instanceof Collection<?> && b instanceof Collection<?>;
    }

    private static boolean bothMaps(Object a, Object b) {
        return a instanceof Map<?, ?> && b instanceof Map<?, ?>;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
