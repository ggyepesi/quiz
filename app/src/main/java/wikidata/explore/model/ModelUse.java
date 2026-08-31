package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Which models a project uses, and what it took from each.
 *
 * <p>This is derived, never stored. A project uses a model exactly when it holds a class
 * imported from it, and imported classes already record that in {@link
 * GeneratedClassModel#importedFrom()}. A second, declared list would be a second way to
 * know the same fact, free to disagree with the classes actually present — the project
 * keeps only one discovery path per fact, so this one is computed.
 *
 * <p>Copied classes are deliberately absent. A copy is the copying project's own and
 * carries no claim from where it came, so it is not a use of anything.
 *
 * <p>The consequence worth knowing: a use appears when the first class is imported and
 * disappears when the last one is removed. There is no way to declare a model as used
 * before importing from it. Nothing needs that yet; if something does, it is a new
 * construct with a reason, not a field quietly added here.
 */
public record ModelUse(String modelName, List<String> classNames) {

    public ModelUse {
        classNames = List.copyOf(classNames);
    }

    /**
     * The models this project uses, in the order their first imported class appears,
     * each with the classes imported from it. A project that imports nothing uses
     * nothing.
     */
    public static List<ModelUse> of(GeneratedProjectModel project) {
        if (project == null) return List.of();

        LinkedHashMap<String, List<String>> byModel = new LinkedHashMap<>();
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) continue;
            String origin = clazz.importedFrom();
            if (origin.isBlank()) continue;
            byModel.computeIfAbsent(origin, key -> new ArrayList<>())
                    .add(clazz.className());
        }

        List<ModelUse> uses = new ArrayList<>();
        byModel.forEach((model, classes) -> uses.add(new ModelUse(model, classes)));
        return List.copyOf(uses);
    }

    /** Whether this project holds any class imported from {@code modelName}. */
    public static boolean uses(GeneratedProjectModel project, String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        return of(project).stream()
                .anyMatch(use -> use.modelName().equalsIgnoreCase(modelName.trim()));
    }

    @Override
    public String toString() {
        return modelName + " · " + classNames.size() + " class(es)";
    }
}
