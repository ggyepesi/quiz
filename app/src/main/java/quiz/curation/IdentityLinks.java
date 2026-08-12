package quiz.curation;

import objectview.Viewable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one place ⟨type, targetId⟩ identity-link keying is decided.
 *
 * <p>A link is written under the instance's STABLE identity type — the same key the object
 * pool, {@code equals} and {@link Merges} use — not under its most-specific class. The
 * most-specific class moves: subclassing an instance, stamping it with a role, or repairing
 * a leaked internal type all change it, and a link keyed on it would then simply stop
 * matching. Nothing errors when that happens; the instance just reads as unidentified
 * again, which is silent data loss.</p>
 *
 * <p>Lifecycle invariant: evidence-derived kinds must be settled before staging identity
 * links. Classification intentionally re-keys a legacy role carrier (Nominee) to its real
 * kind (Person); identity work begun before that boundary belongs to the old model.</p>
 *
 * <p>Reading is deliberately more forgiving than writing: a link already recorded under a
 * subclass ({@code USState} rather than {@code State}) or under a role the instance carries
 * still matches, so sidecars written before this rule keep working without a migration.</p>
 */
public final class IdentityLinks {

    /** The source kind for Wikidata identity links, spelled once. */
    public static final String WIKIDATA = "Wikidata";

    private IdentityLinks() { }

    /** The type a NEW link is written under. */
    public static String stableType(Viewable member) {
        if (member == null) {
            return null;
        }
        if (member instanceof wikidata.explore.extract.WikidataDynamicObject dynamic
                && !dynamic.hasTypeStamp()) {
            return null;
        }
        String stable = member.identityTypeName();
        String fallback = member.typeName();
        String result = stable == null || stable.isBlank() ? fallback : stable;
        return result == null || result.isBlank()
                || "WikidataDynamicObject".equals(result) ? null : result;
    }

    /** Every type an existing link for {@code member} may legitimately carry. */
    public static Set<String> matchableTypes(Viewable member) {
        Set<String> types = new LinkedHashSet<>();
        if (member == null) {
            return types;
        }
        if (member instanceof wikidata.explore.extract.WikidataDynamicObject dynamic
                && !dynamic.hasTypeStamp()) {
            return types;
        }
        add(types, stableType(member));
        add(types, member.typeName());
        Set<String> direct = member.directClassNames();
        if (direct != null) {
            direct.forEach(name -> add(types, name));
        }
        return types;
    }

    /** Whether {@code link} records an identity for {@code member} in any source. */
    public static boolean matches(IdentityLink link, Viewable member) {
        if (link == null || member == null) {
            return false;
        }
        String targetId = member.getIdentifier();
        return targetId != null && !targetId.isBlank()
                && targetId.equals(link.targetId())
                && matchableTypes(member).contains(link.type());
    }

    /** Whether {@code link} records an identity for {@code member} in {@code sourceKind}. */
    public static boolean matches(IdentityLink link, Viewable member, String sourceKind) {
        return matches(link, member)
                && sourceKind != null && sourceKind.equalsIgnoreCase(link.sourceKind());
    }

    /**
     * The Wikidata QID recorded for {@code member}: an approved (durable) link first, then a
     * staged one. Returns null when nothing is recorded — a NATIVE qid is the instance's own
     * identifier and is not this method's business.
     */
    public static String wikidataQid(ManualCuration curation, Viewable member) {
        if (curation == null || member == null) {
            return null;
        }
        String durable = curation.identityLinks().stream()
                .filter(link -> matches(link, member, WIKIDATA))
                .map(IdentityLink::sourceId)
                .findFirst().orElse(null);
        if (durable != null) {
            return durable;
        }
        CurationStaging staging = CurationStaging.forCuration(curation);
        return staging == null ? null : staging.identityLinks().stream()
                .filter(link -> matches(link, member, WIKIDATA))
                .map(IdentityLink::sourceId)
                .findFirst().orElse(null);
    }

    /**
     * Whether this exact assignment is already recorded, durably or staged. Deliberately
     * keyed on ⟨targetId, sourceId⟩ rather than on the type: the question is whether this
     * entity is already linked to this record, and a link written under an older type
     * answers it just as well.
     */
    public static boolean alreadyLinked(
            ManualCuration curation, String targetId, String qid) {
        if (curation == null || targetId == null || qid == null) {
            return false;
        }
        CurationStaging staging = CurationStaging.forCuration(curation);
        return recorded(curation.identityLinks(), targetId, qid)
                || (staging != null && recorded(staging.identityLinks(), targetId, qid));
    }

    private static boolean recorded(
            java.util.List<IdentityLink> links, String targetId, String qid) {
        return links.stream().anyMatch(link -> targetId.equals(link.targetId())
                && WIKIDATA.equalsIgnoreCase(link.sourceKind())
                && qid.equals(link.sourceId()));
    }

    private static void add(Set<String> types, String value) {
        if (value != null && !value.isBlank()) {
            types.add(value);
        }
    }
}
