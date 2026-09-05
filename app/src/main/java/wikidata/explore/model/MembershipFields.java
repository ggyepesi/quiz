package wikidata.explore.model;

import datasource.schema.FieldType;

import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * For a class whose membership is a MULTI-TARGET RELATIONAL collection — entities
 * gathered because {@code ?entity <relationPid> <one of a SET of target QIDs>}
 * (Oscars: P1411 → 59 categories; equally "films in a franchise", "members of an
 * org set", …) — two grouping dimensions are intrinsic to how the entities were
 * gathered, so every such class should carry them as fields:
 *
 * <ul>
 *   <li><b>target</b> — {@code ?entity <relationPid> ?target}, restricted to the
 *       membership target set. The thing that varies ACROSS the set → the natural
 *       partition (category / franchise / award).</li>
 *   <li><b>type</b> — {@code ?entity wdt:P31 ?type}. Mixed per target but
 *       unambiguous per entity → the clean subclass key.</li>
 * </ul>
 *
 * Group by target → per-target ViewableGroups; group by type → subclasses, with
 * no extra discovery. Only the relational (non-P31) multi-target case earns these
 * — a plain {@code P31 = Qx} membership has one type and no target set.
 */
public final class MembershipFields {

    public static final String TARGET_FIELD = "target";
    public static final String TYPE_FIELD = "type";
    private static final String P31 = MembershipPattern.DEFAULT_PROPERTY;

    private MembershipFields() {}

    /**
     * A {@code target} field applies when membership is a non-P31 RELATION to ≥1
     * target (Oscars P1411 → categories). For type-based membership the target is
     * the type itself, so only {@link #appliesType} applies (e.g. Stars).
     */
    public static boolean appliesTarget(GeneratedClassModel clazz) {
        // A statement-reification class's fields come from the statement value +
        // qualifiers, not the intrinsic target/type — don't auto-inject them.
        if (clazz == null || clazz.reifiesStatements()) {
            return false;
        }
        EntityBound membership = clazz.membership();
        String pid = clean(membership.relationPid());
        return MembershipPattern.relational(pid) && !targets(membership).isEmpty();
    }

    /**
     * A {@code type} field applies whenever membership spans MORE THAN ONE type —
     * a non-P31 relation (entities can be any type: person/film) OR a P31
     * membership with several allowed (sub)types (star / red giant / variable
     * star …). Skipped only for a single exact {@code P31 = Qx} membership, where
     * every entity has the same type and the field would be constant.
     */
    public static boolean appliesType(GeneratedClassModel clazz) {
        if (clazz == null || clazz.reifiesStatements()) {
            return false;
        }
        EntityBound membership = clazz.membership();
        String pid = clean(membership.relationPid());
        if (!WikidataIds.isPid(pid)) {
            return false;
        }
        // More than one TYPE, not "at least one ADDITIONAL type": the two were the
        // same question while a membership had a leading QID and a set of extras.
        return MembershipPattern.relational(pid) || targets(membership).size() > 1;
    }

    /**
     * Ensure the intrinsic grouping fields exist (visible, editable real model
     * fields): {@code type} (P31) when membership spans multiple types, plus
     * {@code target} (the relation, restricted to the target set) for the
     * relational case. Deduped by property+direction, so a hand-made equivalent
     * (e.g. Oscars' {@code category} on P1411) is NOT doubled.
     *
     * @return names of the fields actually added (for logging)
     */
    public static List<String> ensure(GeneratedClassModel clazz) {
        List<String> added = new ArrayList<>();
        if (clazz == null) {
            return added;
        }
        EntityBound membership = clazz.membership();
        String pid = clean(membership.relationPid());

        if (appliesType(clazz) && !hasFieldFor(clazz, P31)) {
            clazz.fields().add(typeField());
            added.add(TYPE_FIELD);
        }
        if (appliesTarget(clazz) && !hasFieldFor(clazz, pid)) {
            clazz.fields().add(targetField(pid, targets(membership)));
            added.add(TARGET_FIELD);
        }
        return added;
    }

    private static Set<String> targets(EntityBound membership) {
        Set<String> t = new LinkedHashSet<>();
        for (String q : membership.qids()) {
            String c = clean(q);
            if (WikidataIds.isQid(c)) {
                t.add(c);
            }
        }
        return t;
    }

    // A property is already covered if some field maps it in the ROOT_TO_ITEM
    // direction (the entity's own outgoing property) — independent of field name.
    private static boolean hasFieldFor(GeneratedClassModel clazz, String pid) {
        for (GeneratedFieldModel f : clazz.fields()) {
            if (pid.equalsIgnoreCase(clean(f.mapping().propertyPid()))
                    && f.mapping().direction() == RuleDirection.ROOT_TO_ITEM) {
                return true;
            }
        }
        return false;
    }

    private static GeneratedFieldModel targetField(String pid, Set<String> targets) {
        GeneratedFieldModel f = new GeneratedFieldModel(
                TARGET_FIELD, FieldType.ENTITY, FieldCardinality.COLLECTION);
        f.renderMode(FieldRenderMode.INLINE);
        FieldSourceMapping m = f.mapping();
        m.propertyPid(pid);
        m.direction(RuleDirection.ROOT_TO_ITEM);
        // Restrict to the membership target set so a generic relation (e.g. P1411
        // "nominated for", shared by non-Oscar awards) only yields the targets.
        m.allowedQids().addAll(targets);
        return f;
    }

    private static GeneratedFieldModel typeField() {
        GeneratedFieldModel f = new GeneratedFieldModel(
                TYPE_FIELD, FieldType.ENTITY, FieldCardinality.COLLECTION);
        f.renderMode(FieldRenderMode.INLINE);
        FieldSourceMapping m = f.mapping();
        m.propertyPid(P31);
        m.direction(RuleDirection.ROOT_TO_ITEM);
        return f;
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/'); // tolerate a full entity URI
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
