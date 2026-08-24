package wikidata.explore.model;

import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceRecipe;
import datasource.wikidata.WikidataDatasourceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Makes a source class's population, identity, display name and aliases explicit. */
public final class ClassSourceBindings {
    private ClassSourceBindings() { }

    public static void synchronize(GeneratedProjectModel project) {
        if (project == null) return;
        for (GeneratedClassModel clazz : project.classes()) {
            synchronize(clazz, project);
        }
    }

    public static void synchronize(GeneratedClassModel clazz) {
        synchronize(clazz, null);
    }

    private static void synchronize(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null) return;
        // Only a project can say what a class's population is, because membership can be
        // inherited and a class alone cannot see its base. The project-less form serves
        // an editor that needs the identity/label/alias defaults and never reads this
        // slot; deriving it from the class's own mapping there would CLEAR an inherited
        // population, which is what describing a subclass used to do.
        if (project != null) {
            replace(clazz, SourceBindingSlot.CLASS_POPULATION, population(
                    clazz.className(),
                    PopulationSourceBindings.fromLegacy(clazz, project)));
        }
        if (clazz.classKind() == ClassKind.SOURCE) {
            putDefault(clazz, new SourceBinding(
                    SourceBindingTarget.classIdentity(clazz.className()),
                    new SourceRecipe(WikidataDatasourceProvider.ID,
                            WikidataDatasourceProvider.IDENTIFIER, Map.of())));
            putDefault(clazz, new SourceBinding(
                    SourceBindingTarget.classNames(clazz.className(),
                            SourceBindingSlot.CLASS_LABEL),
                    new SourceRecipe(WikidataDatasourceProvider.ID,
                            WikidataDatasourceProvider.LABEL, Map.of())));
            putDefault(clazz, new SourceBinding(
                    SourceBindingTarget.classNames(clazz.className(),
                            SourceBindingSlot.CLASS_ALIASES),
                    new SourceRecipe(WikidataDatasourceProvider.ID,
                            WikidataDatasourceProvider.ALIASES, Map.of())));
        } else {
            remove(clazz, SourceBindingSlot.CLASS_IDENTITY);
            remove(clazz, SourceBindingSlot.CLASS_LABEL);
            remove(clazz, SourceBindingSlot.CLASS_ALIASES);
        }
    }

    /** Banks pending class edits and returns their bindings after storage validation. */
    static List<SourceBinding> synchronizeAndCollect(GeneratedProjectModel project) {
        synchronize(project);
        List<SourceBinding> result = new ArrayList<>();
        if (project != null) for (GeneratedClassModel clazz : project.classes()) {
            SourceBinding identity = binding(clazz, SourceBindingSlot.CLASS_IDENTITY);
            SourceBinding display = binding(clazz, SourceBindingSlot.CLASS_LABEL);
            if (identity != null && display != null && !identity.recipe().providerId()
                    .equals(display.recipe().providerId())) {
                throw new IllegalArgumentException("Class " + clazz.className()
                        + " must derive identity and its source label from the same datasource");
            }
            for (SourceBinding binding : clazz.sourceBindings()) {
                if (!clazz.className().equals(binding.target().className())) {
                    throw new IllegalArgumentException("Source binding for "
                            + binding.target().className() + " is stored on "
                            + clazz.className());
                }
                result.add(binding);
            }
        }
        return List.copyOf(result);
    }

    public static SourceBinding binding(
            GeneratedClassModel clazz, SourceBindingSlot slot) {
        if (clazz == null || slot == null) return null;
        return clazz.sourceBindings().stream()
                .filter(value -> value.target().slot() == slot)
                .findFirst().orElse(null);
    }

    private static SourceBinding population(String className, SourceRecipe recipe) {
        return recipe == null ? null : new SourceBinding(
                SourceBindingTarget.classPopulation(className), recipe);
    }

    private static void putDefault(GeneratedClassModel clazz, SourceBinding binding) {
        if (clazz.sourceBindings().stream().noneMatch(binding::sameTarget)) {
            clazz.sourceBindings().add(binding);
        }
    }

    private static void replace(GeneratedClassModel clazz, SourceBindingSlot slot,
            SourceBinding replacement) {
        remove(clazz, slot);
        if (replacement != null) clazz.sourceBindings().add(replacement);
    }

    private static void remove(GeneratedClassModel clazz, SourceBindingSlot slot) {
        clazz.sourceBindings().removeIf(binding -> binding.target().slot() == slot);
    }
}
