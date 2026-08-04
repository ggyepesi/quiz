package quiz.source;

import objectview.Viewable;

/** Source-identity queries shared by validation, enrichment, and transform workflows. */
public final class SourceIdentities {
    private SourceIdentities() { }

    /**
     * The instance's own Wikidata source, derived from its stable identity: a
     * Wikidata entity's identifier IS its QID, so the source is read from identity,
     * not from any stored field. Manual instances (non-QID identity) return null —
     * a resolved Wikidata identity for them lives in the curation history, which
     * consumers read separately.
     */
    public static WikidataSource wikidata(Viewable viewable) {
        if (viewable instanceof WikidataSource wikidata) return wikidata;
        if (viewable == null) return null;
        String id = viewable.getIdentifier();
        return id != null && id.matches("Q\\d+")
                ? new WikidataSource(id, viewable.getDisplayName()) : null;
    }

    public static String wikidataQid(Viewable viewable) {
        WikidataSource wikidata = wikidata(viewable);
        return wikidata != null && wikidata.qid().matches("Q\\d+")
                ? wikidata.qid() : null;
    }
}
