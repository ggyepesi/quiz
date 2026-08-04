package quiz.source;

import objectview.Viewable;

/** Source-identity queries shared by validation, enrichment, and transform workflows. */
public final class SourceIdentities {
    private SourceIdentities() { }

    public static WikidataSource wikidata(Viewable viewable) {
        if (viewable instanceof WikidataSource wikidata) return wikidata;
        if (viewable instanceof Sourced anchorable
                && anchorable.anchor() instanceof WikidataSource wikidata) {
            return wikidata;
        }
        return null;
    }

    public static String wikidataQid(Viewable viewable) {
        WikidataSource wikidata = wikidata(viewable);
        return wikidata != null && wikidata.qid().matches("Q\\d+")
                ? wikidata.qid() : null;
    }
}
