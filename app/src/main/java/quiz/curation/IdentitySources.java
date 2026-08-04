package quiz.curation;

import objectview.Viewable;
import quiz.source.Sourced;
import quiz.source.SourceFactory;
import quiz.source.WikidataSource;

import java.util.Collection;
import java.util.List;

/**
 * Resolves durable Wikidata identity links onto instances via the datasource
 * construct: a {@link WikidataLinkSourceFactory} does the <em>identify</em> (link →
 * candidate {@link WikidataSource}); this class does the <em>resolution</em> (a
 * single candidate becomes the instance's anchor). Re-anchoring never changes the
 * instance's own identity.
 */
public final class IdentitySources {
    private IdentitySources() { }

    /** Batch: resolve every instance that a Wikidata link identifies. */
    public static int apply(Collection<? extends Viewable> instances,
                            Collection<IdentityLink> links) {
        if (instances == null || links == null) return 0;
        SourceFactory<WikidataSource> factory = new WikidataLinkSourceFactory(links);
        int count = 0;
        for (Viewable instance : instances) {
            if (resolveAndAnchor(instance, factory)) count++;
        }
        return count;
    }

    /** Single approved link (used by the resolve-identities UI). */
    public static void apply(Viewable target, IdentityLink link) {
        resolveAndAnchor(target, new WikidataLinkSourceFactory(
                link == null ? List.of() : List.of(link)));
    }

    public static void refresh(Viewable target, ManualCuration curation) {
        if (curation == null) return;
        resolveAndAnchor(target, new WikidataLinkSourceFactory(curation.identityLinks()));
    }

    /**
     * Identify {@code instance} through the factory and, when exactly one candidate
     * resolves, set it as the instance's anchor. Skips instances that aren't
     * {@link Sourced}, aren't identified (0 candidates — e.g. a non-Wikidata link),
     * or are ambiguous (&gt;1 — a pick is the UI's job, not this batch's).
     */
    private static boolean resolveAndAnchor(
            Viewable instance, SourceFactory<WikidataSource> factory) {
        if (!(instance instanceof Sourced sourced)) return false;
        List<WikidataSource> candidates;
        try {
            candidates = factory.identify(instance);
        } catch (Exception e) {
            return false;
        }
        if (candidates.size() != 1) return false;
        sourced.anchor(candidates.get(0));
        return true;
    }
}
