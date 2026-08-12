package quiz.enrichment;

import objectview.Viewable;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import quiz.source.SourceIdentities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects an instance's own Wikidata identity and manually approved identities. */
public final class EnrichmentSources {

    private EnrichmentSources() { }

    public static List<EnrichmentProposal.SourceRef> collect(
            Viewable member, String type, ManualCuration curation) {
        Map<String, EnrichmentProposal.SourceRef> result = new LinkedHashMap<>();
        quiz.source.WikidataSource wikidata = SourceIdentities.wikidata(member);
        if (wikidata != null) {
            add(result, "Wikidata", wikidata.qid(), wikidata.wikidataUrl());
        }

        if (curation != null) {
            // Type matching belongs to IdentityLinks: a link recorded under this
            // instance's base class, its subclass or a role it carries all identify it,
            // and missing one reads as "no source" — which sends Find Data down the
            // name-based path instead of the identified one.
            for (IdentityLink link : curation.identityLinks()) {
                if (quiz.curation.IdentityLinks.matches(link, member)) {
                    add(result, link.sourceKind(), link.sourceId(), link.recordUrl());
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    /**
     * Whether enrichment should pause for explicit identity/source approval instead
     * of silently falling through to a name-based provider such as DBpedia.
     */
    public static boolean needsSelection(
            Viewable member, String type, ManualCuration curation) {
        return member != null && curation != null
                && collect(member, type, curation).isEmpty();
    }

    private static void add(Map<String, EnrichmentProposal.SourceRef> result,
                            String kind, String sourceId, String url) {
        EnrichmentProposal.SourceRef source =
                new EnrichmentProposal.SourceRef(kind, sourceId, url);
        result.put(String.valueOf(kind) + '\u0000' + sourceId + '\u0000' + url, source);
    }
}
