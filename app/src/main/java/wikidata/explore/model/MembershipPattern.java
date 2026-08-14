package wikidata.explore.model;

import wikidata.WikidataIds;

import java.util.LinkedHashSet;
import java.util.List;
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
    /** Produced once per owning instance by an ENTITY field marked OWNED_COMPONENT. */
    OWNED_COMPONENT("Owned component"),
    /** A referenced-only ("identity holder") class: it has no membership rule of its
     *  own — its members are DERIVED as the range of a field that targets it (an
     *  entity at the value-end of that field's property). Complete the moment a field
     *  points at it, so never "Unconfigured". Requires project context to detect (see
     *  {@link #of(GeneratedClassModel, GeneratedProjectModel)}). */
    REFERENCED("Referenced only"),
    /** Members are not queried at all: an evidence rule assigns this class to entities
     *  already in the pool (P31 = Q5 → Person). Configured, but by classification rather
     *  than by a membership query — so it must not read as "Unconfigured". */
    EVIDENCE_KIND("Evidence-derived kind"),
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

    /** Where a referenced-only class's membership is derived from: the field that
     *  targets it, its owner class, and the property (direct or qualifier) whose
     *  range the class IS. */
    public record DerivedFrom(String ownerClass, String fieldName, String pid) {}
    public record OwnedBy(String ownerClass, String fieldName) {}

    /** Project-aware classification: a class with no membership of its own is
     *  REFERENCED (an identity holder) when some field targets it; otherwise falls
     *  back to the single-argument {@link #of(GeneratedClassModel)}. */
    public static MembershipPattern of(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        MembershipPattern base = of(clazz);
        if (base != UNCONFIGURED) {
            return base;
        }
        if (!ownedBy(clazz, project).isEmpty()) {
            return OWNED_COMPONENT;
        }
        // REFERENCED first: being a field's target drives real machinery (role
        // inference, referent field loads), which an evidence rule does not.
        if (derivedFrom(clazz, project) != null) {
            return REFERENCED;
        }
        return kindRule(clazz, project) == null ? base : EVIDENCE_KIND;
    }

    /** Field-defined production sites; the target class carries no duplicate owner config. */
    public static List<OwnedBy> ownedBy(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null || project == null) return List.of();
        String target = clean(clazz.className());
        if (target.isEmpty()) return List.of();
        java.util.ArrayList<OwnedBy> sites = new java.util.ArrayList<>();
        for (GeneratedClassModel owner : project.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field != null && field.type() == FieldType.ENTITY
                        && field.mapping().productionKind()
                                == FieldProductionKind.OWNED_COMPONENT
                        && target.equals(clean(field.entityClassName()))) {
                    sites.add(new OwnedBy(owner.className(), field.name()));
                }
            }
        }
        return List.copyOf(sites);
    }

    /** The field that derives a referenced-only class's membership, or null. The
     *  first ENTITY field in the project whose target names this class. */
    public static DerivedFrom derivedFrom(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null || project == null) {
            return null;
        }
        String name = clean(clazz.className());
        if (name.isEmpty()) {
            return null;
        }
        for (GeneratedClassModel owner : project.classes()) {
            if (owner == null) {
                continue;
            }
            for (GeneratedFieldModel f : owner.fields()) {
                if (f == null || f.type() != FieldType.ENTITY) {
                    continue;
                }
                if (name.equals(clean(f.entityClassName()))) {
                    String pid = clean(f.mapping().qualifierPid());
                    if (!pid.matches("(?i)P\\d+")) {
                        pid = clean(f.mapping().propertyPid());
                    }
                    return new DerivedFrom(owner.className(), f.name(), pid);
                }
            }
        }
        return null;
    }

    /** The configured evidence rule naming this class, or null. */
    public static EntityKindRule kindRule(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null || project == null) {
            return null;
        }
        String name = clean(clazz.className());
        if (name.isEmpty()) {
            return null;
        }
        for (EntityKindRule rule : project.entityKindRules()) {
            if (rule != null && rule.isConfigured() && name.equals(clean(rule.className()))) {
                return rule;
            }
        }
        return null;
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

        if (!WikidataIds.isPid(pid)) {
            return seeded ? SEEDED : UNCONFIGURED;
        }
        if (pid.equals("P31")) {
            if (m.additionalTypeQids().stream().anyMatch(q -> WikidataIds.isQid(clean(q)))) {
                return MULTI_TYPE;
            }
            if (WikidataIds.isQid(clean(m.sourceQid()))) {
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

    /** Project-aware label: a referenced-only class reads as "Derived from
     *  Nomination.forWork (P1686)" instead of "Unconfigured"; otherwise the
     *  single-argument {@link #describe(GeneratedClassModel)}. */
    public static String describe(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (of(clazz, project) == OWNED_COMPONENT) {
            List<OwnedBy> sites = ownedBy(clazz, project);
            String shown = sites.stream().map(s -> s.ownerClass() + "." + s.fieldName())
                    .collect(java.util.stream.Collectors.joining(", "));
            return "Owned by " + shown;
        }
        if (of(clazz, project) == REFERENCED) {
            DerivedFrom d = derivedFrom(clazz, project);
            String via = d.ownerClass() + "." + d.fieldName();
            return "Derived from " + via
                    + (d.pid().matches("(?i)P\\d+") ? " (" + d.pid() + ")" : "");
        }
        EntityKindRule kind = kindRule(clazz, project);
        if (of(clazz, project) == EVIDENCE_KIND && kind != null) {
            List<String> qids = kind.evidenceQids();
            String shown = String.join(", ", qids.subList(0, Math.min(3, qids.size())));
            return EVIDENCE_KIND.label + " (" + kind.propertyPid() + " = " + shown
                    + (qids.size() > 3 ? ", +" + (qids.size() - 3) : "") + ")";
        }
        return describe(clazz);
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
            // REFERENCED needs project context to name its deriving field, so it is
            // only produced by of(clazz, project); reached here only if a caller uses
            // the single-arg path, where the bare label is the best we can say.
            case OWNED_COMPONENT, REFERENCED, EVIDENCE_KIND, UNCONFIGURED -> p.label;
        };
    }

    private static int targetCount(FieldSourceMapping m) {
        Set<String> t = new LinkedHashSet<>();
        String src = clean(m.sourceQid());
        if (WikidataIds.isQid(src)) {
            t.add(src);
        }
        for (String q : m.additionalTypeQids()) {
            String c = clean(q);
            if (WikidataIds.isQid(c)) {
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
