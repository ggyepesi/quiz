package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import quiz.transform.TypeSpec;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import domain.DelegatingDomainModel;
import domain.DomainModel;
import domain.DomainSchemas;

/** Group-scoped schema projection driven exclusively by an explicit {@link TypeSpec}. */
final class TypeSpecDomainView extends DelegatingDomainModel {
    private static final String VIRTUAL = "@type-spec:";
    private final TypeSpec spec;
    private final TypeSpecPaths paths;

    TypeSpecDomainView(DomainModel base, TypeSpec spec) {
        super(base);
        this.spec = java.util.Objects.requireNonNull(spec);
        this.paths = new TypeSpecPaths(base, spec);
    }

    // Everything the base declares is forwarded by DelegatingDomainModel. The three below
    // are declarations this view answers for ITSELF, because they describe a schema and the
    // schema here is the projected one: asking the base about a virtual @type-spec: type
    // would get nothing back, and asking it about the real class would describe fields this
    // projection has removed.
    @Override public Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    @Override public objectview.viewconfig.FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }

    @Override public boolean entityOrigin(String type, objectview.field.FieldPath path) {
        objectview.field.FieldRef field = DomainSchemas.resolve(this, type, path);
        return field != null && field.reference();
    }

    @Override public FieldSchema fieldSchema(String type) {
        if (type == null) return null;
        if (type.equals(spec.instanceClass())) {
            return schemaAt("", Set.of(spec.instanceClass()));
        }
        if (type.startsWith(VIRTUAL)) {
            // The path's classes, NOT only the explicitly restricted ones: an intermediate
            // segment is typed by its declared target, so descending through it keeps the
            // fields the plain class already had (forWork.releaseDate) while the nested
            // restriction still refines what it names (forWork.creator : Person).
            String path = type.substring(VIRTUAL.length());
            return schemaAt(path, paths.classesOrNone(path));
        }
        return base.fieldSchema(type);
    }

    private FieldSchema schemaAt(String path, Set<String> declaredTypes) {
        LinkedHashMap<String, FieldRef> fields = new LinkedHashMap<>();
        for (String type : declaredTypes) {
            FieldSchema schema = base.fieldSchema(type);
            if (schema != null) for (FieldRef field : schema.fields()) {
                fields.putIfAbsent(field.name(), field);
            }
        }
        for (Map.Entry<String, FieldRef> entry : new java.util.ArrayList<>(fields.entrySet())) {
            FieldRef field = entry.getValue();
            String fieldPath = path.isBlank() ? field.name() : path + "." + field.name();
            Set<String> explicit = spec.fieldClasses().get(fieldPath);
            boolean hasDescendant = spec.fieldClasses().keySet().stream()
                    .anyMatch(candidate -> candidate.startsWith(fieldPath + "."));
            if (explicit == null && !hasDescendant) continue;
            Set<String> targets = paths.classesOrNone(fieldPath);
            if (!field.reference() || targets.isEmpty()) continue;
            String label = String.join(" | ", targets);
            fields.put(entry.getKey(), FieldRef.described(
                    field.name(), field.label(), field.role(), field.kind(), field.valueKind(),
                    field.collection() ? "List<" + label + ">" : label,
                    true, field.collection(), VIRTUAL + fieldPath,
                    field.structural(), field.minor(), field.inline(), field.embedded(), field.link(),
                    field.linkText(), field.annotatedReference()));
        }
        List<FieldRef> immutable = List.copyOf(fields.values());
        return () -> immutable;
    }
}
