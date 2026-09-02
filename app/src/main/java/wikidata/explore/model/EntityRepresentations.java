package wikidata.explore.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** Shared interpretation of a project's ordered contextual representation rules. */
public final class EntityRepresentations {
    private EntityRepresentations() { }

    /** A representation target together with its nearest inherited admission rule. */
    public record Admission(String className, EntityKindRule evidence) { }

    public static java.util.List<Admission> admissions(GeneratedProjectModel model) {
        if (model == null) return java.util.List.of();
        java.util.LinkedHashMap<String, Admission> found = new java.util.LinkedHashMap<>();
        for (EntityRepresentationRule representation : model.entityRepresentationRules()) {
            if (representation == null || !representation.isConfigured()) continue;
            GeneratedClassModel target = model.resolveClass(
                    representation.representationClassId(),
                    representation.representationClassName());
            EntityKindRule evidence = MembershipPattern.kindRule(target, model);
            if (target != null && evidence != null) {
                found.putIfAbsent(target.className(),
                        new Admission(target.className(), evidence));
            }
        }
        return java.util.List.copyOf(found.values());
    }

    /** The first explicitly configured representation whose admission matched. */
    public static String preferredClass(GeneratedProjectModel model,
                                        Set<String> matchedClasses) {
        if (model == null || matchedClasses == null) return null;
        return model.entityRepresentationRules().stream()
                .filter(java.util.Objects::nonNull)
                .map(EntityRepresentationRule::representationClassName)
                .filter(matchedClasses::contains)
                .findFirst().orElse(null);
    }

    /** Compatibility role stamps replaced by the matching representations. */
    public static Set<String> replacedRoleClasses(GeneratedProjectModel model,
                                                   Set<String> matchedClasses) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (model == null || matchedClasses == null) return roles;
        for (EntityRepresentationRule rule : model.entityRepresentationRules()) {
            if (rule != null && matchedClasses.contains(rule.representationClassName())) {
                roles.add(rule.roleClassName());
            }
        }
        return roles;
    }
}
