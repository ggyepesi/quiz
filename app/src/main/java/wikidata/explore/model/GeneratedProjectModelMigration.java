package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Normalizes model files saved before the explicit StatementClass model.
 *
 * <p>The migration is deliberately idempotent: it is safe to call after every
 * load and immediately before every save. Once migrated, runtime and editor
 * code can use {@link GeneratedClassModel#statementSource()},
 * {@link FieldSourceMapping#missingQualifierPolicy()} and
 * {@link CanonicalSpec#keyFields()} without inspecting legacy fields.</p>
 */
public final class GeneratedProjectModelMigration {

    private GeneratedProjectModelMigration() {
    }

    /**
     * Migrates the supplied project in place.
     *
     * @return a summary of the changes made
     */
    public static Result migrate(GeneratedProjectModel project) {
        if (project == null) {
            return Result.empty();
        }

        int statementSources = 0;
        int qualifierPolicies = 0;
        int canonicalSpecs = 0;
        int dedupFlagsCleared = 0;

        // rootClass is normally also present in classes(), but older serialized
        // models may temporarily contain a distinct root object. Use identity
        // de-duplication so both representations are normalized exactly once.
        List<GeneratedClassModel> classes = distinctClasses(project);

        for (GeneratedClassModel clazz : classes) {
            if (clazz == null) {
                continue;
            }

            boolean legacyStatementClass =
                    clazz.statementSource() != null
                            && !clazz.hasExplicitStatementSource();

            if (legacyStatementClass) {
                // statementSource() synthesizes a compatibility view from
                // statementSourceClass + instanceMapping.propertyPid().
                clazz.statementSource(clazz.statementSource());
                statementSources++;
            }

            boolean hadLegacyDedupFlags = hasLegacyDedupFlags(clazz);
            if (!clazz.hasCanonical()
                    && clazz.reifiesStatements()
                    && hadLegacyDedupFlags) {

                // effectiveCanonical() converts the old per-field flags into
                // the class-level natural key while retaining the old default
                // handling for DATE and derived fields.
                clazz.canonical(
                        clazz.effectiveCanonical().copy());
                canonicalSpecs++;
            }

            for (GeneratedFieldModel field : clazz.fields()) {
                if (field == null) {
                    continue;
                }

                FieldSourceMapping mapping = field.mapping();

                if (mapping.hasLegacySubjectDefault()) {
                    MissingQualifierPolicy policy =
                            mapping.missingQualifierPolicy();
                    mapping.missingQualifierPolicy(policy);
                    qualifierPolicies++;
                }

                if (mapping.inDedupKey() != null
                        && clazz.hasCanonical()) {
                    mapping.inDedupKey(null);
                    dedupFlagsCleared++;
                }
            }
        }

        upgradeSelections(project);

        return new Result(
                statementSources,
                qualifierPolicies,
                canonicalSpecs,
                dedupFlagsCleared);
    }

    /**
     * Upgrades any selection whose runtime class is exactly the base
     * {@link Selection} to its concrete subtype by {@link Selection#kind()}. A stray
     * base carries no data fields, so only name+kind need to be carried. Defensive:
     * models are normally saved with the concrete {@code @class}; this catches any
     * that predate the split. Rewrites the list only if at least one was upgraded.
     */
    private static void upgradeSelections(GeneratedProjectModel project) {
        List<Selection> current = project.selections();
        boolean anyUpgraded = false;
        List<Selection> upgraded = new ArrayList<>(current.size());

        for (Selection s : current) {
            if (s != null && s.getClass() == Selection.class) {
                Selection replacement = switch (s.kind()) {
                    case VOCABULARY -> new VocabularySelection(s.name());
                    case POPULATION -> new PopulationSelection(s.name());
                };
                upgraded.add(replacement);
                anyUpgraded = true;
            } else {
                upgraded.add(s);
            }
        }

        if (anyUpgraded) {
            project.replaceSelections(upgraded);
        }
    }

    private static boolean hasLegacyDedupFlags(
            GeneratedClassModel clazz) {

        return clazz.fields().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(GeneratedFieldModel::mapping)
                    .anyMatch(mapping -> mapping.inDedupKey() != null);
    }

    private static List<GeneratedClassModel> distinctClasses(
            GeneratedProjectModel project) {

        List<GeneratedClassModel> result = new ArrayList<>();
        java.util.Set<GeneratedClassModel> seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());

        if (project.rootClass() != null
                && seen.add(project.rootClass())) {
            result.add(project.rootClass());
        }

        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz != null && seen.add(clazz)) {
                result.add(clazz);
            }
        }

        return result;
    }

    public record Result(
            int statementSources,
            int qualifierPolicies,
            int canonicalSpecs,
            int dedupFlagsCleared) {

        public static Result empty() {
            return new Result(0, 0, 0, 0);
        }

        public boolean changed() {
            return statementSources > 0
                    || qualifierPolicies > 0
                    || canonicalSpecs > 0
                    || dedupFlagsCleared > 0;
        }

        @Override
        public String toString() {
            if (!changed()) {
                return "No model migration was required.";
            }

            return "Migrated "
                    + statementSources + " statement source(s), "
                    + qualifierPolicies + " qualifier fallback(s), "
                    + canonicalSpecs + " canonical specification(s), and "
                    + dedupFlagsCleared + " legacy dedup flag(s).";
        }
    }
}
