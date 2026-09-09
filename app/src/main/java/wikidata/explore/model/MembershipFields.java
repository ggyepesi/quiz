package wikidata.explore.model;

import wikidata.WikidataIds;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * For a class whose membership is a MULTI-TARGET RELATIONAL collection — entities
 * gathered because {@code ?entity <relationPid> <one of a SET of target QIDs>}
 * (Oscars: P1411 → 59 categories; equally "films in a franchise", "members of an
 * org set", …) — two grouping dimensions can be useful explicit field choices:
 *
 * <ul>
 *   <li><b>target</b> — {@code ?entity <relationPid> ?target}, restricted to the
 *       membership target set. The thing that varies ACROSS the set → the natural
 *       partition (category / franchise / award).</li>
 *   <li><b>type</b> — {@code ?entity wdt:P31 ?type}. Mixed per target but
 *       unambiguous per entity → the clean subclass key.</li>
 * </ul>
 *
 * Group by target → per-target ViewableGroups; group by type → subclasses. This
 * class only tells the advisor when those choices are relevant. It never adds fields:
 * the population rule and the class's declared fields are separate authored facts.
 */
public final class MembershipFields {

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

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/'); // tolerate a full entity URI
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
