package wikidata.explore.model;

import java.util.List;

/**
 * The single rule for which modeled class may extend which other class.
 *
 * <p>The editor and validator must ask the same question. Keeping the candidate filter
 * in a Swing panel and the refusal in validation made the UI merely a second, fallible
 * description of the model rule.
 */
public final class ClassExtensionRules {

    private ClassExtensionRules() { }

    public static boolean mayExtend(
            GeneratedClassModel clazz, GeneratedClassModel candidate) {
        if (clazz == null || candidate == null || clazz == candidate) return false;
        return !clazz.ownedClass() || candidate.ownedClass();
    }

    public static List<GeneratedClassModel> candidates(
            GeneratedProjectModel project, GeneratedClassModel clazz) {
        if (project == null || clazz == null) return List.of();
        return project.classes().stream()
                .filter(candidate -> mayExtend(clazz, candidate))
                .toList();
    }
}
