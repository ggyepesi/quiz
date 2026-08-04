package quiz.curation;

import objectview.Viewable;
import quiz.source.Anchorable;
import quiz.source.WikidataViewable;

import java.util.Collection;
import java.util.List;

/** Applies durable Wikidata identity links to Wikidata-backed instances. */
public final class IdentitySources {
    private IdentitySources() { }

    public static int apply(Collection<? extends Viewable> instances,
                            Collection<IdentityLink> links) {
        if (instances == null || links == null) return 0;
        int count = 0;
        for (IdentityLink link : links) {
            Viewable target = instances.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(instance -> java.util.Objects.equals(
                            instance.typeName(), link.type()))
                    .filter(instance -> java.util.Objects.equals(
                            instance.getIdentifier(), link.targetId()))
                    .findFirst().orElse(null);
            // Only a Wikidata link names an identity this construct can apply. A
            // non-Wikidata cross-reference (e.g. a NobelPrize.org laureate ID) is
            // recorded in curation as provenance but is NOT the object's Wikidata
            // anchor — applying it would overwrite the qid with a foreign id — so
            // it is skipped rather than aborting the whole batch.
            if (target != null && applicable(target, link)) {
                apply(target, link);
                count++;
            }
        }
        return count;
    }

    public static void apply(Viewable target, IdentityLink link) {
        if (target == null || link == null) return;
        if (!isWikidataLink(link)) {
            throw new IllegalArgumentException(
                    "Unsupported identity provenance: " + link.sourceKind());
        }
        if (!(target instanceof Anchorable anchorable)) {
            throw new IllegalArgumentException(
                    target.typeName() + " does not accept a source anchor");
        }
        // Re-anchor: swap in a Wikidata source descriptor. Identity is untouched,
        // so the object stays valid in any pool it already sits in.
        anchorable.anchor(
                new WikidataViewable(link.sourceId(), link.canonicalName()));
    }

    /** A link this construct can apply: a Wikidata provenance onto a carrier that
     *  accepts a source anchor. The non-throwing form of {@link #apply}'s
     *  preconditions, used to filter a mixed batch. */
    private static boolean applicable(Viewable target, IdentityLink link) {
        return target instanceof Anchorable && isWikidataLink(link);
    }

    private static boolean isWikidataLink(IdentityLink link) {
        return link != null && "Wikidata".equalsIgnoreCase(link.sourceKind());
    }

    public static void refresh(Viewable target, ManualCuration curation) {
        if (target == null) return;
        IdentityLink link = curation == null ? null : curation.identityLinks().stream()
                .filter(candidate -> java.util.Objects.equals(
                        candidate.type(), target.typeName()))
                .filter(candidate -> java.util.Objects.equals(
                        candidate.targetId(), target.getIdentifier()))
                .findFirst().orElse(null);
        if (applicable(target, link)) {
            apply(target, link);
        }
    }
}
