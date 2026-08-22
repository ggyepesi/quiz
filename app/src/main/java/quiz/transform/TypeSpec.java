package quiz.transform;

import objectview.Viewable;
import objectview.field.FieldAccess;
import domain.DomainModel;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit instance + per-field classes for one group; never inferred from samples. */
public record TypeSpec(String instanceClass, Map<String, Set<String>> fieldClasses) {

    public TypeSpec {
        instanceClass = instanceClass == null ? "" : instanceClass.trim();
        LinkedHashMap<String, Set<String>> fields = new LinkedHashMap<>();
        if (fieldClasses != null) fieldClasses.forEach((path, types) -> {
            Set<String> clean = clean(types);
            if (path != null && !path.isBlank() && !clean.isEmpty()) {
                fields.put(path.trim(), clean);
            }
        });
        fieldClasses = java.util.Collections.unmodifiableMap(fields);
    }

    public boolean isConfigured() { return !instanceClass.isBlank(); }

    /** Parent constraints inherited by a child; the child may refine the same path. */
    public TypeSpec refinedBy(TypeSpec child) {
        if (child == null) return this;
        if (!instanceClass.equals(child.instanceClass)) {
            throw new IllegalArgumentException("Nested type-spec groups must use the same "
                    + "instance class: " + instanceClass + " vs " + child.instanceClass);
        }
        LinkedHashMap<String, Set<String>> combined = new LinkedHashMap<>(fieldClasses);
        combined.putAll(child.fieldClasses);
        return new TypeSpec(instanceClass, combined);
    }

    /** AND between the instance/field constraints, OR among a field's classes, ALL of a
     *  multi-valued field's values (see {@link #matchesPath}). */
    public boolean matches(Viewable instance, DomainModel domain) {
        if (instance == null || domain == null || !isConfigured()
                || !domain.isInstanceOf(instance, instanceClass)) return false;
        for (Map.Entry<String, Set<String>> rule : fieldClasses.entrySet()) {
            if (!matchesPath(instance, rule.getKey().split("\\."), 0,
                    rule.getValue(), domain)) return false;
        }
        return true;
    }

    /**
     * Walks the path one segment at a time, requiring EVERY value it reaches to be of an
     * accepted class. Within the group this path's effective class IS the restriction —
     * that is what lets the group show Person's fields on a nominee site — so a single
     * off-kind value would make the projected schema a lie. A missing or empty value
     * holds nothing of the required kind and is not admitted either.
     *
     * <p>Walked rather than read through {@code getPath}/{@code getPathValues}: the first
     * collapses a multi-valued intermediate to whichever element comes first, and the
     * second drops the owners that have no value at all — both would admit an instance
     * the restriction does not hold for.
     */
    private static boolean matchesPath(Object owner, String[] segments, int index,
                                       Set<String> accepted, DomainModel domain) {
        // A persisted multi-valued reference arrives as a map keyed by identifier.
        if (owner instanceof Map<?, ?> values) {
            return matchesPath(values.values(), segments, index, accepted, domain);
        }
        if (owner instanceof Collection<?> values) {
            return !values.isEmpty() && values.stream()
                    .allMatch(value -> matchesPath(value, segments, index, accepted, domain));
        }
        if (index == segments.length) {
            return owner instanceof Viewable viewable
                    && accepted.stream().anyMatch(type -> domain.isInstanceOf(viewable, type));
        }
        return owner != null && matchesPath(FieldAccess.getPath(owner, segments[index]),
                segments, index + 1, accepted, domain);
    }

    public String encodeFieldClasses() {
        return fieldClasses.entrySet().stream()
                .map(e -> e.getKey() + "=" + String.join("|", e.getValue()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public static TypeSpec decode(String instanceClass, String fields) {
        LinkedHashMap<String, Set<String>> decoded = new LinkedHashMap<>();
        if (fields != null) for (String line : fields.split("\\R")) {
            int split = line.indexOf('=');
            if (split > 0) decoded.put(line.substring(0, split),
                    split(line.substring(split + 1)));
        }
        return new TypeSpec(instanceClass, decoded);
    }

    private static Set<String> split(String value) {
        return value == null || value.isBlank() ? Set.of()
                : clean(List.of(value.split("\\|")));
    }

    private static Set<String> clean(Collection<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values != null) values.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(s -> !s.isBlank()).forEach(out::add);
        return java.util.Collections.unmodifiableSet(out);
    }
}
