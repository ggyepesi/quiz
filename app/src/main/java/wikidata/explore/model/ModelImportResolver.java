package wikidata.explore.model;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;

/** Resolves live model references into the effective configuration used by a project. */
public final class ModelImportResolver {
    @FunctionalInterface
    public interface Repository {
        GeneratedProjectModel load(String modelName) throws Exception;
    }

    private ModelImportResolver() { }

    public static GeneratedProjectModel resolve(
            GeneratedProjectModel authored, Repository repository) {
        return resolve(authored, repository, new ArrayDeque<>());
    }

    private static GeneratedProjectModel resolve(GeneratedProjectModel authored,
            Repository repository, ArrayDeque<String> stack) {
        if (authored == null) throw new IllegalArgumentException("Project model is required");
        if (authored.imports().isEmpty()) return authored.copy();
        if (repository == null) throw new IllegalStateException(
                "A model repository is required for imports");

        GeneratedProjectModel effective = authored.copy();
        for (ModelImport reference : authored.imports()) {
            if (reference == null || !reference.complete()) {
                throw new IllegalStateException("Incomplete model import");
            }
            String key = reference.modelName().toLowerCase(java.util.Locale.ROOT);
            if (stack.contains(key)) {
                throw new IllegalStateException("Cyclic model import: "
                        + String.join(" -> ", stack) + " -> " + reference.modelName());
            }
            GeneratedProjectModel source;
            try {
                stack.addLast(key);
                source = resolve(repository.load(reference.modelName()), repository, stack);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot resolve model '"
                        + reference.modelName() + "': " + failure.getMessage(), failure);
            } finally {
                if (!stack.isEmpty() && stack.getLast().equals(key)) stack.removeLast();
            }
            if (!source.isModel()) {
                throw new IllegalStateException("Imported project '" + source.name()
                        + "' is not a model");
            }
            if (!source.name().equalsIgnoreCase(reference.modelName())) {
                throw new IllegalStateException("Import names model '"
                        + reference.modelName() + "' but the resolved file contains '"
                        + source.name() + "'");
            }
            for (String className : reference.classNames()) {
                try {
                    ClassImportPlan plan = ClassImportPlan.of(source, effective, className);
                    LinkedHashSet<String> closure = new LinkedHashSet<>();
                    closure.add(className);
                    closure.addAll(plan.dependencyClassNames());
                    plan.apply(closure, ClassImportPlan.Ownership.IMPORT);
                } catch (IllegalStateException alreadyExplained) {
                    throw alreadyExplained;
                } catch (RuntimeException failure) {
                    // A class the import names but the model no longer has arrives here
                    // as an IllegalArgumentException from deep in the import plan. Only
                    // IllegalStateException is turned into an IOException by the loader,
                    // so unwrapped it escapes load() as a stack trace. What to DO about
                    // a model that moved under its importers is deliberately open; that
                    // is no reason for the failure to be illegible in the meantime.
                    throw new IllegalStateException("Cannot import "
                            + reference.modelName() + "." + className + ": "
                            + failure.getMessage(), failure);
                }
            }
        }
        return effective;
    }
}
