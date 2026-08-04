package quiz.source;

import objectview.Viewable;

/** Source-identity queries shared by validation, enrichment, and transform workflows. */
public final class SourceIdentities {
    private SourceIdentities() { }

    public static WikidataViewable wikidata(Viewable viewable) {
        if (viewable instanceof WikidataViewable wikidata) return wikidata;
        if (viewable instanceof Anchorable anchorable
                && anchorable.anchor() instanceof WikidataViewable wikidata) {
            return wikidata;
        }
        return null;
    }

    public static String wikidataQid(Viewable viewable) {
        WikidataViewable wikidata = wikidata(viewable);
        return wikidata != null && wikidata.qid().matches("Q\\d+")
                ? wikidata.qid() : null;
    }
}
