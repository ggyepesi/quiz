package wikidata.explore.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named classification of how a class gathers its members — the "shape" of its
 * membership configuration — so the pattern is explicit and visible (e.g. on the
 * class tree node) instead of having to be inferred from the relation pid + QID
 * sets. Drives which intrinsic grouping fields apply (see {@link MembershipFields}).
 */
public enum MembershipPattern {

    /** No usable membership relation and no seed QIDs yet. */
    UNCONFIGURED("Unconfigured"),
    /** Instances are REIFIED from statements — configured by a statement property
     *  (+ an optional source class or discovered subjects), not by a membership
     *  query. So a statement class is never "Unconfigured". */
    REIFIED("Reified statements"),
    /** {@code P31 = Qx} — every member is the same single type. */
    SINGLE_TYPE("Single type"),
    /** {@code P31 ∈ {type, subtypes…}} — members span several (sub)types. */
    MULTI_TYPE("Type + subtypes"),
    /** {@code <relation> → one target} (a non-P31 relation to a single entity). */
    SINGLE_TARGET_RELATION("Single-target relation"),
    /** {@code <relation> → {target set}} (e.g. P1411 → award categories). */
    MULTI_TARGET_RELATION("Multi-target relation"),
    /** Members are an explicit curated QID list (seed QIDs). */
    SEEDED("Seeded");

    private final String label;

    MembershipPattern(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Classify a class's membership configuration. */
    public static MembershipPattern of(GeneratedClassModel clazz) {
        if (clazz == null) {
            return UNCONFIGURED;
        }
        // A statement/reify class draws its members from a statement property (+ an
        // optional source class or discovered subjects), not from a membership query —
        // so it's configured by its reify, never "Unconfigured".
        if (clazz.reifiesStatements()) {
            return REIFIED;
        }
        FieldSourceMapping m = clazz.instanceMapping();
        String pid = clean(m.propertyPid());
        boolean seeded = !clazz.seedQids().isEmpty();

        if (!pid.matches("P\\d+")) {
            return seeded ? SEEDED : UNCONFIGURED;
        }
        if (pid.equals("P31")) {
            if (m.additionalTypeQids().stream().anyMatch(q -> clean(q).matches("Q\\d+"))) {
                return MULTI_TYPE;
            }
            if (clean(m.sourceQid()).matches("Q\\d+")) {
                return SINGLE_TYPE;
            }
            return seeded ? SEEDED : UNCONFIGURED;
        }
        int targets = targetCount(m);
        if (targets > 1) {
            return MULTI_TARGET_RELATION;
        }
        if (targets == 1) {
            return SINGLE_TARGET_RELATION;
        }
        return seeded ? SEEDED : UNCONFIGURED;
    }

    /** Label plus a short concrete detail, e.g. "Multi-target relation (P1411 → 59)". */
    public static String describe(GeneratedClassModel clazz) {
        MembershipPattern p = of(clazz);
        if (clazz == null) {
            return p.label;
        }
        FieldSourceMapping m = clazz.instanceMapping();
        String pid = clean(m.propertyPid());
        return switch (p) {
            case REIFIED -> {
                StatementClassSource s = clazz.statementSource();
                String prop = s == null ? "" : clean(s.propertyPid());
                String origin = s != null && s.hasSourceClass()
                        ? " of " + s.sourceClassName()
                        : " · discovered";
                String domain = s != null && s.hasValueSelection()
                        ? " → Selection '" + s.valueSelectionName() + "'"
                        : "";
                yield p.label + " (" + prop + origin + domain + ")";
            }
            case SINGLE_TYPE -> p.label + " (" + clean(m.sourceQid()) + ")";
            case MULTI_TYPE -> p.label + " (P31, +"
                    + m.additionalTypeQids().size() + ")";
            case SINGLE_TARGET_RELATION, MULTI_TARGET_RELATION ->
                    p.label + " (" + pid + " → " + targetCount(m) + ")";
            case SEEDED -> p.label + " (" + clazz.seedQids().size() + ")";
            case UNCONFIGURED -> p.label;
        };
    }

    private static int targetCount(FieldSourceMapping m) {
        Set<String> t = new LinkedHashSet<>();
        String src = clean(m.sourceQid());
        if (src.matches("Q\\d+")) {
            t.add(src);
        }
        for (String q : m.additionalTypeQids()) {
            String c = clean(q);
            if (c.matches("Q\\d+")) {
                t.add(c);
            }
        }
        return t.size();
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
