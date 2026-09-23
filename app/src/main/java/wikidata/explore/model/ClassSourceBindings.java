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

    /**
     * The name slots every Source class has, and the operation each falls back to.
     * Enumerated once: declaring the defaults and describing them are the same fact,
     * and a describer with its own copy of this list goes stale the moment one changes.
     */
    private static final Map<SourceBindingSlot, String> REQUIRED_NAME_SOURCES =
            requiredNameSources();

    private static Map<SourceBindingSlot, String> requiredNameSources() {
        // Identity first, then the label: the order the editor reads them out, so a
        // LinkedHashMap rather than Map.of, whose iteration order is not the one written.
        Map<SourceBindingSlot, String> sources = new java.util.LinkedHashMap<>();
        sources.put(SourceBindingSlot.CLASS_IDENTITY,
                WikidataDatasourceProvider.IDENTIFIER);
        sources.put(SourceBindingSlot.CLASS_LABEL, WikidataDatasourceProvider.LABEL);
        return java.util.Collections.unmodifiableMap(sources);
    }

    /**
     * Declares the required identity and label sources for a newly edited Source
     * class without silently opting it into optional alias acquisition.
     */
    public static void declareRequiredNameSources(GeneratedClassModel clazz) {
        if (clazz == null || clazz.classKind() != ClassKind.SOURCE) return;
        REQUIRED_NAME_SOURCES.forEach((slot, operation) ->
                putDefault(clazz, classBinding(clazz, slot, operation)));
    }

    /**
     * The name sources a class uses: the bindings it stores, and for a required slot it
     * has not stored, the default it would be given.
     *
     * <p>Read-only, because an editor describes a class by opening it. Reading the
     * stored bindings alone answered "—" for a class whose names come from the
     * defaults — blank where the control's own vocabulary means "nothing configured",
     * so a class behaving exactly as declared read as unconfigured. Materializing the
     * defaults to have something to describe is the other wrong answer: it writes to the
     * model on the way past.
     */
    public static List<SourceBinding> effectiveNameBindings(GeneratedClassModel clazz) {
        if (clazz == null || clazz.classKind() != ClassKind.SOURCE) return List.of();
        List<SourceBinding> effective = new ArrayList<>();
        REQUIRED_NAME_SOURCES.forEach((slot, operation) -> {
            SourceBinding stored = binding(clazz, slot);
            effective.add(stored != null ? stored : classBinding(clazz, slot, operation));
        });
        SourceBinding aliases = binding(clazz, SourceBindingSlot.CLASS_ALIASES);
        if (aliases != null) effective.add(aliases);
        return List.copyOf(effective);
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
            // A legacy class has no name bindings at all. Materialize the historical
            // id + label + aliases behaviour once. After that, absence is meaningful:
            // an editor may deliberately remove aliases and synchronization must not
            // silently opt the class back in.
            boolean legacy = binding(clazz, SourceBindingSlot.CLASS_IDENTITY) == null
                    && binding(clazz, SourceBindingSlot.CLASS_LABEL) == null
                    && binding(clazz, SourceBindingSlot.CLASS_ALIASES) == null;
            declareRequiredNameSources(clazz);
            if (legacy) putDefault(clazz, classBinding(clazz,
                    SourceBindingSlot.CLASS_ALIASES,
                    WikidataDatasourceProvider.ALIASES));
        } else {
            remove(clazz, SourceBindingSlot.CLASS_IDENTITY);
            remove(clazz, SourceBindingSlot.CLASS_LABEL);
            remove(clazz, SourceBindingSlot.CLASS_ALIASES);
        }
    }

    /** Banks pending class edits and returns their bindings after storage validation. */
    static List<SourceBinding> synchronizeAndCollect(GeneratedProjectModel project) {
        synchronize(project);
        return collect(project);
    }

    /** Reads and validates already-banked bindings without changing the model. */
    static List<SourceBinding> collect(GeneratedProjectModel project) {
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

    /** Explicitly chooses whether Wikidata aliases are retained for this class. */
    public static void aliases(GeneratedClassModel clazz, boolean enabled) {
        if (clazz == null) return;
        remove(clazz, SourceBindingSlot.CLASS_ALIASES);
        if (enabled && clazz.classKind() == ClassKind.SOURCE) {
            clazz.sourceBindings().add(classBinding(clazz,
                    SourceBindingSlot.CLASS_ALIASES,
                    WikidataDatasourceProvider.ALIASES));
        }
    }

    /**
     * Whether the generated class exposes and retains aliases.
     *
     * <p>The no-binding case is the legacy model shape and preserves its historical
     * aliases. Once identity/label have been materialized, an absent alias binding is
     * an explicit opt-out rather than another legacy default.
     */
    public static boolean aliasesEnabled(GeneratedClassModel clazz) {
        if (clazz == null || clazz.classKind() != ClassKind.SOURCE) return false;
        if (binding(clazz, SourceBindingSlot.CLASS_ALIASES) != null) return true;
        return binding(clazz, SourceBindingSlot.CLASS_IDENTITY) == null
                && binding(clazz, SourceBindingSlot.CLASS_LABEL) == null;
    }

    private static SourceBinding classBinding(GeneratedClassModel clazz,
            SourceBindingSlot slot, String operation) {
        SourceBindingTarget target = slot == SourceBindingSlot.CLASS_IDENTITY
                ? SourceBindingTarget.classIdentity(clazz.className())
                : SourceBindingTarget.classNames(clazz.className(), slot);
        return new SourceBinding(target, new SourceRecipe(
                WikidataDatasourceProvider.ID, operation, Map.of()));
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
