package domain.curation;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** One identity lookup for curation: every configured class an object carries plus its
 * primary type names the same {@code <type, id>} instance. */
final class TypedInstanceIndex<T extends Viewable> {

    private final Map<Key, T> byKey = new LinkedHashMap<>();
    private final Map<String, List<T>> byId = new LinkedHashMap<>();

    static <T extends Viewable> TypedInstanceIndex<T> of(
            Collection<? extends T> instances) {
        TypedInstanceIndex<T> index = new TypedInstanceIndex<>();
        if (instances != null) instances.forEach(index::add);
        return index;
    }

    void add(T instance) {
        if (instance == null || instance.getIdentifier() == null) return;
        for (String type : typesOf(instance)) {
            byKey.putIfAbsent(new Key(type, instance.getIdentifier()), instance);
        }
        byId.computeIfAbsent(instance.getIdentifier(), ignored -> new ArrayList<>())
                .add(instance);
    }

    T get(String type, String id) {
        return byKey.get(new Key(type, id));
    }

    /**
     * The one loaded instance with {@code id} that is a {@code type}: of that class or,
     * through the domain's {@code baseType} hierarchy, a subclass of it. A field typed
     * NorseCharacter accepts a NorseDeity; an unrelated class with the same identifier
     * is not a match, and two different matching instances are no answer.
     */
    T getAssignable(String type, String id, java.util.function.Function<String, String> baseType) {
        T exact = get(type, id);
        if (exact != null || type == null || baseType == null) return exact;
        T match = null;
        for (T candidate : get(id)) {
            if (!isA(candidate, type, baseType) || candidate == match) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    private static boolean isA(Viewable instance, String type,
            java.util.function.Function<String, String> baseType) {
        for (String own : typesOf(instance)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String current = own; current != null && seen.add(current);
                 current = baseType.apply(current)) {
                if (current.equals(type)) return true;
            }
        }
        return false;
    }

    List<T> get(String id) {
        return byId.getOrDefault(id, List.of());
    }

    static boolean hasType(Viewable instance, String type) {
        return type != null && typesOf(instance).contains(type);
    }

    private static LinkedHashSet<String> typesOf(Viewable instance) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (instance == null) return types;
        types.addAll(instance.directClassNames());
        types.add(instance.typeName());
        types.add(instance.identityTypeName());
        types.removeIf(type -> type == null || type.isBlank());
        return types;
    }

    private record Key(String type, String id) { }
}
