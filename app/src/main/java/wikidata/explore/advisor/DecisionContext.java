package wikidata.explore.advisor;

import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.transform.TransformConfig;

/**
 * The model+transform state a {@link StructuralDecision} reads to decide whether
 * it applies and is resolved. Pure (no network): all predicates run off the
 * already-configured model and the domain's saved Transform. The data-dependent
 * answers (is the type mixed? is the relation denormalized?) are gathered by the
 * info-tool the decision points at, not computed here.
 */
public record DecisionContext(
        GeneratedProjectModel project,
        GeneratedClassModel clazz,
        TransformConfig transform) {

    public MembershipPattern pattern() {
        return MembershipPattern.of(clazz);
    }

    public boolean relational() {
        MembershipPattern p = pattern();
        return p == MembershipPattern.SINGLE_TARGET_RELATION
                || p == MembershipPattern.MULTI_TARGET_RELATION;
    }

    public String relationPid() {
        return clazz == null ? "" : cleanPid(clazz.instanceMapping().propertyPid());
    }

    public int targetCount() {
        if (clazz == null) {
            return 0;
        }
        java.util.Set<String> t = new java.util.LinkedHashSet<>();
        String src = cleanQid(clazz.instanceMapping().sourceQid());
        if (src.matches("Q\\d+")) {
            t.add(src);
        }
        for (String q : clazz.instanceMapping().additionalTypeQids()) {
            String c = cleanQid(q);
            if (c.matches("Q\\d+")) {
                t.add(c);
            }
        }
        return t.size();
    }

    public boolean hasFieldWithPid(String pid) {
        if (clazz == null || pid == null) {
            return false;
        }
        for (GeneratedFieldModel f : clazz.fields()) {
            if (pid.equals(cleanPid(f.mapping().propertyPid()))) {
                return true;
            }
        }
        return false;
    }

    public boolean hasTypeField() {
        return hasFieldWithPid("P31");
    }

    public boolean hasTargetField() {
        String r = relationPid();
        return r.matches("P\\d+") && !r.equals("P31") && hasFieldWithPid(r);
    }

    /** Name of the P31 (type) field, for matching a facet/subclass against it. */
    public String typeFieldName() {
        if (clazz == null) {
            return "";
        }
        for (GeneratedFieldModel f : clazz.fields()) {
            if ("P31".equals(cleanPid(f.mapping().propertyPid()))) {
                return f.name();
            }
        }
        return "";
    }

    public boolean hasSubclass() {
        if (project == null || clazz == null) {
            return false;
        }
        return project.classes().stream().anyMatch(
                c -> c != clazz && clazz.className().equals(c.baseClassName()));
    }

    public boolean hasFacetOnField(String fieldName) {
        return fieldName != null && !fieldName.isBlank() && clazz != null
                && clazz.facets().stream().anyMatch(f -> fieldName.equals(f.fieldName()));
    }

    public boolean hasAnyFacet() {
        return clazz != null && !clazz.facets().isEmpty();
    }

    public boolean qualifiersLoaded() {
        if (transform == null || clazz == null) {
            return false;
        }
        return transform.qualifierLoads.stream().anyMatch(q ->
                relationPid().equals(cleanPid(q.propertyPid()))
                        && (q.entityType() == null
                            || q.entityType().equals(clazz.className())));
    }

    public boolean reified() {
        if (transform == null || clazz == null) {
            return false;
        }
        return transform.reifies.stream().anyMatch(
                r -> clazz.className().equals(r.sourceType()));
    }

    static String cleanPid(String s) {
        return tail(s);
    }

    static String cleanQid(String s) {
        return tail(s);
    }

    private static String tail(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
