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

/** Group-scoped schema projection driven exclusively by an explicit {@link TypeSpec}. */
final class TypeSpecDomainView implements DomainModel {
    private static final String VIRTUAL = "@type-spec:";
    private final DomainModel base;
    private final TypeSpec spec;
    private final TypeSpecPaths paths;

    TypeSpecDomainView(DomainModel base, TypeSpec spec) {
        this.base = java.util.Objects.requireNonNull(base);
        this.spec = java.util.Objects.requireNonNull(spec);
        this.paths = new TypeSpecPaths(base, spec);
    }

    @Override public List<String> types() { return base.types(); }
    @Override public List<String> servedTypes() { return base.servedTypes(); }
    @Override public String baseType(String type) { return base.baseType(type); }
    @Override public Set<String> directClasses(Viewable instance) { return base.directClasses(instance); }
    @Override public Collection<? extends Viewable> instances() { return base.instances(); }
    @Override public Collection<? extends Viewable> memberRoots() { return base.memberRoots(); }
    @Override public List<String> selectionNames() { return base.selectionNames(); }
    @Override public List<Viewable> selectionMembers(String name) { return base.selectionMembers(name); }
    @Override public boolean exposesEntityUniverse() { return base.exposesEntityUniverse(); }
    @Override public Class<? extends Viewable> universe() { return base.universe(); }

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
                    field.structural(), field.minor(), field.inline(), field.link(),
                    field.linkText(), field.annotatedReference()));
        }
        List<FieldRef> immutable = List.copyOf(fields.values());
        return () -> immutable;
    }
}
