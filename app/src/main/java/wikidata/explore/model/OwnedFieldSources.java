package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;

/** One resolution path for shared defaults and ownership-site field-source overrides. */
public final class OwnedFieldSources {
    private OwnedFieldSources() { }

    public static OwnedFieldSource override(
            GeneratedFieldModel ownershipField, String componentFieldPath) {
        if (ownershipField == null) return null;
        String path = clean(componentFieldPath);
        return ownershipField.ownedFieldSources().stream()
                .filter(value -> value != null && path.equals(value.fieldPath()))
                .findFirst().orElse(null);
    }

    public static OwnedFieldSource ensureOverride(
            GeneratedFieldModel ownershipField, String componentFieldPath) {
        if (ownershipField == null) {
            throw new IllegalArgumentException("Ownership field is required");
        }
        String path = clean(componentFieldPath);
        if (path.isBlank()) {
            throw new IllegalArgumentException("Owned component field path is required");
        }
        OwnedFieldSource existing = override(ownershipField, path);
        if (existing != null) return existing;
        OwnedFieldSource created = new OwnedFieldSource(path);
        ownershipField.ownedFieldSources().add(created);
        return created;
    }

    public static void removeOverride(
            GeneratedFieldModel ownershipField, String componentFieldPath) {
        if (ownershipField == null) return;
        String path = clean(componentFieldPath);
        ownershipField.ownedFieldSources().removeIf(value -> value != null
                && path.equals(value.fieldPath()));
    }

    /** Fields of the shared component with this site's acquisition overrides applied. */
    public static List<GeneratedFieldModel> effectiveFields(
            GeneratedProjectModel project, GeneratedClassModel target,
            OwnedComponentSite site) {
        if (target == null) return List.of();
        GeneratedFieldModel ownership = ownershipField(project, site);
        List<GeneratedFieldModel> result = new ArrayList<>();
        for (GeneratedFieldModel field : target.effectiveFields(project)) {
            if (field != null) result.add(resolve(field, field.name(), ownership));
        }
        return List.copyOf(result);
    }

    public static GeneratedFieldModel effectiveField(
            GeneratedProjectModel project, GeneratedClassModel target,
            OwnedComponentSite site, String path) {
        List<GeneratedFieldModel> fields = effectiveFields(project, target, site);
        GeneratedFieldModel found = null;
        for (String segment : clean(path).split("\\.")) {
            if (segment.isBlank()) return null;
            found = fields.stream().filter(field -> field != null
                    && segment.equals(field.name())).findFirst().orElse(null);
            if (found == null) return null;
            fields = found.fields();
        }
        return found;
    }

    public static GeneratedFieldModel ownershipField(
            GeneratedProjectModel project, OwnedComponentSite site) {
        if (project == null || site == null) return null;
        GeneratedClassModel owner = project.findClass(site.ownerClass());
        if (owner == null) return null;
        return owner.fields().stream().filter(field -> field != null
                        && site.ownerField().equals(field.name())
                        && site.targetClass().equals(field.entityClassName())
                        && OwnedClassSemantics.isOwnerQidField(field, project))
                .findFirst().orElse(null);
    }

    private static GeneratedFieldModel resolve(
            GeneratedFieldModel declared, String path,
            GeneratedFieldModel ownershipField) {
        GeneratedFieldModel resolved = declared.copy();
        OwnedFieldSource override = override(ownershipField, path);
        if (override != null) {
            resolved.mapping().copyAcquisitionFrom(override.mapping());
            resolved.fallbackMapping(override.fallbackMapping() == null ? null
                    : override.fallbackMapping().copy());
            resolved.wikipediaCategoryRule(override.wikipediaCategoryRule() == null ? null
                    : override.wikipediaCategoryRule().copy());
            resolved.sourceBindings().clear();
            resolved.sourceBindings().addAll(override.sourceBindings());
        }
        List<GeneratedFieldModel> children = new ArrayList<>(resolved.fields());
        resolved.fields().clear();
        for (GeneratedFieldModel child : children) {
            resolved.fields().add(resolve(child, path + "." + child.name(), ownershipField));
        }
        return resolved;
    }

    public static GeneratedFieldModel declaredField(
            GeneratedProjectModel project, String className, String path) {
        GeneratedClassModel owner = project == null ? null : project.findClass(className);
        if (owner == null) return null;
        List<GeneratedFieldModel> fields = owner.effectiveFields(project);
        GeneratedFieldModel found = null;
        for (String segment : clean(path).split("\\.")) {
            if (segment.isBlank()) return null;
            found = fields.stream().filter(field -> field != null
                    && segment.equals(field.name())).findFirst().orElse(null);
            if (found == null) return null;
            fields = found.fields();
        }
        return found;
    }

    /** Path of a declared field relative to one class, preserving nested ownership. */
    public static String declaredFieldPath(
            GeneratedClassModel owner, GeneratedFieldModel sought) {
        if (owner == null || sought == null) return "";
        return declaredFieldPath(owner.fields(), sought, "");
    }

    private static String declaredFieldPath(
            List<GeneratedFieldModel> fields, GeneratedFieldModel sought, String parent) {
        for (GeneratedFieldModel candidate : fields) {
            if (candidate == null) continue;
            String path = parent.isBlank() ? candidate.name()
                    : parent + "." + candidate.name();
            if (candidate == sought) return path;
            String nested = declaredFieldPath(candidate.fields(), sought, path);
            if (!nested.isBlank()) return nested;
        }
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
