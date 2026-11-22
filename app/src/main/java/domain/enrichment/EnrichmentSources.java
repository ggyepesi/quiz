package domain.enrichment;

import domain.datasource.SourceRef;

import objectview.Viewable;
import domain.curation.IdentityLink;
import domain.curation.ManualCuration;
import domain.provenance.SourceIdentities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects an instance's own Wikidata identity and manually approved identities. */
public final class EnrichmentSources {

    private EnrichmentSources() { }

    public static List<SourceRef> collect(
            Viewable member, String type, ManualCuration curation) {
        Map<String, SourceRef> result = new LinkedHashMap<>();
        domain.provenance.WikidataSource wikidata = SourceIdentities.wikidata(member);
        if (wikidata != null) {
            add(result, "Wikidata", wikidata.qid(), wikidata.wikidataUrl());
        }

        if (curation != null) {
            // Type matching belongs to IdentityLinks: a link recorded under this
            // instance's base class, its subclass or a role it carries all identify it,
            // and missing one reads as "no source" — which sends Find Data down the
            // name-based path instead of the identified one.
            for (IdentityLink link : curation.identityLinks()) {
                if (domain.curation.IdentityLinks.matches(link, member)) {
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

    private static void add(Map<String, SourceRef> result,
                            String kind, String sourceId, String url) {
        SourceRef source =
                new SourceRef(kind, sourceId, url);
        result.put(String.valueOf(kind) + '\u0000' + sourceId + '\u0000' + url, source);
    }
}
