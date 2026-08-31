package wikidata.explore.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves a domain's exact module pins into one validation/compilation model. */
public final class ModelImportResolver {
    public record ResolvedModule(ModelModuleImport pin, ModelModule module) { }

    private ModelImportResolver() { }

    public static GeneratedProjectModel resolve(
            GeneratedProjectModel domain, ModelModuleResolver repository) {
        if (domain == null) throw new IllegalArgumentException("domain model is required");
        if (domain.imports().isEmpty()) return domain.copy();
        if (repository == null) {
            throw new IllegalStateException("A model-module repository is required for "
                    + domain.imports().size() + " import(s)");
        }

        List<ResolvedModule> modules = modules(domain, repository);

        GeneratedProjectModel resolved = domain.copy();
        for (ResolvedModule imported : modules) merge(resolved, imported.module());
        applyPresentationOverlays(resolved);
        resolved.ensureDeclarationIdentities();
        return resolved;
    }

    private static void applyPresentationOverlays(GeneratedProjectModel resolved) {
        for (ModelClassPresentationOverlay overlay
                : resolved.modulePresentationOverlays()) {
            GeneratedClassModel clazz = resolved.findClassById(
                    overlay.classDeclarationId());
            if (clazz == null) {
                throw new IllegalStateException("Presentation overlay targets unavailable class "
                        + overlay.classDeclarationId());
            }
            CanonicalSpec canonical = clazz.canonical();
            canonical.displayNameMode(overlay.displayNameMode());
            canonical.displayNameField(overlay.displayNameField());
            canonical.displayNameTemplate(overlay.displayNameTemplate());
        }
    }

    /** The same dependency order used for composition, exposed for origin-aware UI. */
    public static List<ResolvedModule> modules(
            GeneratedProjectModel domain, ModelModuleResolver repository) {
        if (domain == null) throw new IllegalArgumentException("domain model is required");
        if (domain.imports().isEmpty()) return List.of();
        if (repository == null) {
            throw new IllegalStateException("A model-module repository is required for "
                    + domain.imports().size() + " import(s)");
        }
        LinkedHashMap<String, ModelModule> ordered = new LinkedHashMap<>();
        LinkedHashMap<String, String> versionByModule = new LinkedHashMap<>();
        ArrayDeque<String> stack = new ArrayDeque<>();
        for (ModelModuleImport dependency : domain.imports()) {
            visit(dependency, repository, ordered, versionByModule, stack);
        }
        return ordered.values().stream().map(module -> new ResolvedModule(
                new ModelModuleImport(module.moduleId(), module.version(),
                        module.contentDigest(), module.declarationIds()), module))
                .toList();
    }

    private static void visit(ModelModuleImport pin, ModelModuleResolver repository,
            Map<String, ModelModule> ordered, Map<String, String> versionByModule,
            ArrayDeque<String> stack) {
        if (pin == null || !pin.complete()) {
            throw new IllegalStateException("Incomplete model-module import");
        }
        String coordinate = pin.coordinate();
        if (stack.contains(coordinate)) {
            List<String> cycle = new ArrayList<>(stack);
            cycle.add(coordinate);
            throw new IllegalStateException("Cyclic model-module import: "
                    + String.join(" -> ", cycle));
        }
        if (ordered.containsKey(coordinate)) {
            try {
                verify(pin, ordered.get(coordinate));
            } catch (Exception mismatch) {
                if (mismatch instanceof IllegalStateException state) throw state;
                throw new IllegalStateException("Cannot verify repeated model module "
                        + coordinate + ": " + mismatch.getMessage(), mismatch);
            }
            return;
        }
        String prior = versionByModule.putIfAbsent(pin.moduleId(), coordinate);
        if (prior != null && !prior.equals(coordinate)) {
            throw new IllegalStateException("Incompatible model-module versions: "
                    + prior + " and " + coordinate);
        }

        ModelModule module;
        try {
            module = repository.resolve(pin.moduleId(), pin.version());
            if (module == null) throw new IllegalStateException("repository returned null");
            module.normalizeDeclarations();
            verify(pin, module);
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Cannot resolve model module " + coordinate
                    + ": " + failure.getMessage(), failure);
        }

        stack.addLast(coordinate);
        for (ModelModuleImport dependency : module.imports()) {
            visit(dependency, repository, ordered, versionByModule, stack);
        }
        stack.removeLast();
        ordered.put(coordinate, module);
    }

    private static void verify(ModelModuleImport pin, ModelModule module) throws Exception {
        if (!pin.moduleId().equals(module.moduleId())
                || !pin.version().equals(module.version())) {
            throw new IllegalStateException("Repository returned " + module.coordinate()
                    + " for " + pin.coordinate());
        }
        String actual = ModelModuleStore.digest(module);
        if (!actual.equals(module.contentDigest()) || !actual.equals(pin.contentDigest())) {
            throw new IllegalStateException("Model-module digest mismatch for "
                    + pin.coordinate() + ": pinned " + pin.contentDigest()
                    + ", module " + module.contentDigest() + ", actual " + actual);
        }
        Set<String> pinned = new LinkedHashSet<>(pin.declarationIds());
        Set<String> exported = new LinkedHashSet<>(module.declarationIds());
        if (!pinned.equals(exported)) {
            throw new IllegalStateException("Model-module declaration set changed for "
                    + pin.coordinate() + ": pinned " + pinned + ", module " + exported);
        }
    }

    private static void merge(GeneratedProjectModel target, ModelModule module) {
        for (GeneratedClassModel imported : module.classes()) {
            GeneratedClassModel sameId = target.findClassById(imported.declarationId());
            GeneratedClassModel sameName = target.findClass(imported.className());
            if (sameId != null || sameName != null) {
                throw collision(module, imported.className(), imported.declarationId(),
                        sameId, sameName);
            }
            if (target.findSelection(imported.className()) != null) {
                throw new IllegalStateException("Module " + module.coordinate()
                        + " class '" + imported.className()
                        + "' collides with a local/imported selection");
            }
            target.addClass(imported.copy());
        }
        for (Selection imported : module.selections()) {
            Selection sameId = target.findSelectionById(imported.declarationId());
            Selection sameName = target.findSelection(imported.name());
            if (sameId != null || sameName != null || target.findClass(imported.name()) != null) {
                throw new IllegalStateException("Module " + module.coordinate()
                        + " selection '" + imported.name()
                        + "' collides with a local or imported declaration");
            }
            target.addSelection(imported.copy());
        }
        for (EntityKindRule rule : module.entityKindRules()) {
            EntityKindRule clash = ruleForSameSubject(target, rule);
            if (clash != null) {
                throw new IllegalStateException("Module " + module.coordinate()
                        + " kind rule for '" + rule.className() + "' on "
                        + rule.propertyPid() + " collides with a local or imported rule "
                        + "for the same class and property. Adopt/replace the local "
                        + "declaration explicitly before importing.");
            }
            target.addEntityKindRule(rule.copy());
        }
    }

    /**
     * A kind rule is identified by the class it stamps and the property it reads — the
     * pair {@code replaceEntityKindRule} overwrites on, so two of them were never meant
     * to coexist. Merging appended without looking, and a module carrying the rule a
     * domain already had produced two entries for one rule, with nothing to say which
     * evidence applied. Resolved by identity where both sides have one, since that is
     * what a reference means now.
     */
    private static EntityKindRule ruleForSameSubject(
            GeneratedProjectModel target, EntityKindRule rule) {
        for (EntityKindRule existing : target.entityKindRules()) {
            if (existing != null && existing.sameTarget(rule)) return existing;
        }
        return null;
    }

    private static IllegalStateException collision(ModelModule module, String name,
            String id, GeneratedClassModel sameId, GeneratedClassModel sameName) {
        String cause = sameId != null
                ? "declaration identity " + id
                : "name '" + name + "'";
        return new IllegalStateException("Module " + module.coordinate() + " class '"
                + name + "' collides with a local or imported class by " + cause
                + ". Adopt/replace the local declaration explicitly before importing.");
    }
}
