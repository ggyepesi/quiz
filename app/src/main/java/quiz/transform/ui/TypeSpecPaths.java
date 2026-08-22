package quiz.transform.ui;

import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import quiz.transform.TypeSpec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import domain.DomainModel;

/**
 * What class a dotted path holds inside a {@link TypeSpec}'s scope: the spec's explicit
 * restriction where it states one, otherwise the field's declared target class.
 *
 * <p>ONE walk, shared by the admission-time validation and the group-scoped schema
 * projection. The two must agree about every path: a rule the projection cannot resolve
 * would admit instances into a group that then shows FEWER fields than the plain class
 * does — the refinement turning into a loss.
 */
final class TypeSpecPaths {

    private final DomainModel base;
    private final TypeSpec spec;

    TypeSpecPaths(DomainModel base, TypeSpec spec) {
        this.base = Objects.requireNonNull(base);
        this.spec = Objects.requireNonNull(spec);
    }

    /** Rejects every constrained path the projection could not resolve, with the reason. */
    void validate() {
        for (String path : spec.fieldClasses().keySet()) {
            classesAt(path);
        }
    }

    /** The same walk without the diagnosis: an unresolvable path holds no class. */
    Set<String> classesOrNone(String path) {
        try {
            return classesAt(path);
        } catch (IllegalArgumentException unresolvable) {
            return Set.of();
        }
    }

    /**
     * The classes the value at {@code path} has within this spec's scope.
     *
     * @throws IllegalArgumentException when a segment is not a reference field of the
     *         classes reached so far, or a segment's class cannot be determined at all.
     */
    Set<String> classesAt(String path) {
        Set<String> current = Set.of(spec.instanceClass());
        StringBuilder prefix = new StringBuilder();
        for (String segment : path.split("\\.")) {
            if (prefix.length() > 0) prefix.append('.');
            prefix.append(segment);
            List<FieldRef> references = current.stream()
                    .map(base::fieldSchema).filter(Objects::nonNull)
                    .map(schema -> field(schema, segment)).filter(Objects::nonNull)
                    .filter(FieldRef::reference).toList();
            if (references.isEmpty()) {
                throw new IllegalArgumentException(
                        "'" + prefix + "' is not a known reference field on any of "
                                + current + " (in '" + path + "').");
            }
            current = classesOf(prefix.toString(), references);
            if (current.isEmpty()) {
                throw new IllegalArgumentException("Cannot constrain '" + path
                        + "': the class of '" + prefix + "' is unknown — constrain '"
                        + prefix + "' explicitly first.");
            }
        }
        return current;
    }

    /** The spec's own restriction wins over the declared target — that IS the refinement.
     *  Where the spec says nothing, the declared target still types the path, which is
     *  what lets a nested rule (forWork.creator) be stated without also restating the
     *  intermediate (forWork). */
    private Set<String> classesOf(String path, List<FieldRef> references) {
        Set<String> explicit = spec.fieldClasses().get(path);
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        LinkedHashSet<String> declared = new LinkedHashSet<>();
        references.stream().map(FieldRef::targetType)
                .filter(type -> type != null && !type.isBlank()).forEach(declared::add);
        return declared;
    }

    private static FieldRef field(FieldSchema schema, String name) {
        for (FieldRef field : schema.fields()) {
            if (field.name().equals(name)) return field;
        }
        return null;
    }
}
