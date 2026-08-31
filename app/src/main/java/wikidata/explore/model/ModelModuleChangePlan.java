package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Previewable, explicit change to a domain's exact module pins. */
public final class ModelModuleChangePlan {
    public enum Action { ADD, UPDATE, REMOVE }

    private final GeneratedProjectModel target;
    private final ModelModuleResolver modules;
    private final Action action;
    private final ModelModuleImport before;
    private final ModelModuleImport after;
    private final GeneratedProjectModel candidate;
    private final List<String> impact;

    private ModelModuleChangePlan(GeneratedProjectModel target,
            ModelModuleResolver modules, Action action, ModelModuleImport before,
            ModelModuleImport after) {
        if (target == null || modules == null) {
            throw new IllegalArgumentException("A domain and module repository are required");
        }
        this.target = target;
        this.modules = modules;
        this.action = action;
        this.before = before == null ? null : before.copy();
        this.after = after == null ? null : after.copy();
        ModelImportResolver.resolve(target, modules);
        List<ModelImportResolver.ResolvedModule> previousModules =
                ModelImportResolver.modules(target, modules);
        this.candidate = target.copy();
        if (action == Action.ADD) {
            if (candidate.imports().stream().anyMatch(i -> i.moduleId().equals(after.moduleId()))) {
                throw new IllegalStateException("Module " + after.moduleId()
                        + " is already imported; use Update.");
            }
            candidate.addImport(after);
        } else if (action == Action.UPDATE) {
            requireCurrent(candidate, before);
            if (!before.moduleId().equals(after.moduleId())) {
                throw new IllegalStateException("An update cannot change the module id");
            }
            candidate.replaceImport(after);
        } else {
            requireCurrent(candidate, before);
            candidate.removeImport(before.moduleId());
        }
        // The preview is also the refusal point: an action that leaves dangling
        // references or introduces a collision is never offered as harmless.
        GeneratedProjectModel composed = ModelImportResolver.resolve(candidate, modules);
        var validation = GeneratedProjectModelValidator.validate(composed);
        if (!validation.valid()) {
            throw new IllegalStateException("The module change would make the domain invalid:\n"
                    + validation.format());
        }
        List<ModelImportResolver.ResolvedModule> nextModules =
                ModelImportResolver.modules(candidate, modules);
        impact = describeImpact(action, previousModules, nextModules);
    }

    public static ModelModuleChangePlan add(GeneratedProjectModel target,
            ModelModuleResolver modules, ModelModuleImport pin) {
        return new ModelModuleChangePlan(target, modules, Action.ADD, null, pin);
    }

    public static ModelModuleChangePlan update(GeneratedProjectModel target,
            ModelModuleResolver modules, ModelModuleImport from, ModelModuleImport to) {
        return new ModelModuleChangePlan(target, modules, Action.UPDATE, from, to);
    }

    public static ModelModuleChangePlan remove(GeneratedProjectModel target,
            ModelModuleResolver modules, ModelModuleImport pin) {
        return new ModelModuleChangePlan(target, modules, Action.REMOVE, pin, null);
    }

    public Action action() { return action; }
    public ModelModuleImport before() { return before == null ? null : before.copy(); }
    public ModelModuleImport after() { return after == null ? null : after.copy(); }
    public List<String> impact() { return impact; }

    public String summary() {
        String coordinate = after != null ? after.coordinate() : before.coordinate();
        return switch (action) {
            case ADD -> "Import " + coordinate;
            case UPDATE -> "Update " + before.coordinate() + " → " + after.coordinate();
            case REMOVE -> "Remove " + coordinate;
        };
    }

    public void apply() {
        target.imports(candidate.imports());
        Set<String> available = ModelImportResolver.modules(candidate, modules).stream()
                .flatMap(resolved -> resolved.pin().declarationIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        target.modulePresentationOverlays(target.modulePresentationOverlays().stream()
                .filter(overlay -> available.contains(overlay.classDeclarationId()))
                .toList());
    }

    private static void requireCurrent(GeneratedProjectModel model, ModelModuleImport pin) {
        boolean present = pin != null && model.imports().stream().anyMatch(item ->
                item.moduleId().equals(pin.moduleId())
                        && item.version().equals(pin.version())
                        && item.contentDigest().equals(pin.contentDigest()));
        if (!present) throw new IllegalStateException("The selected module pin is no longer current");
    }

    private static List<String> describeImpact(Action action,
            List<ModelImportResolver.ResolvedModule> before,
            List<ModelImportResolver.ResolvedModule> after) {
        java.util.Map<String, GeneratedClassModel> oldClasses = classes(before);
        java.util.Map<String, GeneratedClassModel> newClasses = classes(after);
        java.util.Map<String, Selection> oldSelections = selections(before);
        java.util.Map<String, Selection> newSelections = selections(after);
        List<String> lines = new ArrayList<>();
        for (GeneratedClassModel clazz : newClasses.values()) {
                GeneratedClassModel prior = oldClasses.get(clazz.declarationId());
                if (prior == null) {
                    lines.add("Adds class " + clazz.className() + fields(clazz));
                } else if (!classShape(prior).equals(classShape(clazz))) {
                    lines.add("Changes class " + clazz.className() + fields(clazz));
                    if (!canonicalShape(prior).equals(canonicalShape(clazz))) {
                        lines.add("Identity or display-name rules change for "
                                + clazz.className() + ".");
                    }
                }
        }
        for (Selection selection : newSelections.values()) {
            if (!oldSelections.containsKey(selection.declarationId())) {
                lines.add("Adds vocabulary / population " + selection.name());
            }
        }
        for (GeneratedClassModel clazz : oldClasses.values()) {
            if (!newClasses.containsKey(clazz.declarationId())) {
                lines.add("Removes class " + clazz.className() + fields(clazz));
            }
        }
        for (Selection selection : oldSelections.values()) {
            if (!newSelections.containsKey(selection.declarationId())) {
                lines.add("Removes vocabulary / population " + selection.name());
            }
        }
        if (action == Action.UPDATE && lines.isEmpty()) {
            lines.add("Keeps the same classes, fields, mappings and identity rules.");
        }
        if (action == Action.REMOVE) {
            lines.add("Future generation no longer includes declarations supplied only by this module.");
        }
        lines.add("Generated instances are unchanged until the domain is regenerated or remapped.");
        return List.copyOf(lines);
    }

    private static java.util.Map<String, GeneratedClassModel> classes(
            List<ModelImportResolver.ResolvedModule> modules) {
        java.util.LinkedHashMap<String, GeneratedClassModel> result = new java.util.LinkedHashMap<>();
        for (ModelImportResolver.ResolvedModule module : modules) {
            for (GeneratedClassModel clazz : module.module().classes()) {
                result.put(clazz.declarationId(), clazz);
            }
        }
        return result;
    }

    private static java.util.Map<String, Selection> selections(
            List<ModelImportResolver.ResolvedModule> modules) {
        java.util.LinkedHashMap<String, Selection> result = new java.util.LinkedHashMap<>();
        for (ModelImportResolver.ResolvedModule module : modules) {
            for (Selection selection : module.module().selections()) {
                result.put(selection.declarationId(), selection);
            }
        }
        return result;
    }

    private static String fields(GeneratedClassModel clazz) {
        if (clazz.fields().isEmpty()) return " (no fields)";
        return " (fields: " + clazz.fields().stream().map(GeneratedFieldModel::name)
                .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }

    private static com.fasterxml.jackson.databind.JsonNode classShape(
            GeneratedClassModel clazz) {
        return GeneratedProjectModelStore.modelMapper().valueToTree(clazz);
    }

    private static List<Object> canonicalShape(GeneratedClassModel clazz) {
        CanonicalSpec spec = clazz.canonical();
        return List.of(List.copyOf(spec.keyFields()), spec.duplicatePolicy(),
                spec.displayNameMode(), spec.displayNameField(),
                spec.displayNameTemplate(), spec.primaryListField());
    }
}
