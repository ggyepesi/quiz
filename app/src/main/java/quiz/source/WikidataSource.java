package quiz.source;

/**
 * {@link Source} backed by Wikidata: a QID plus its wikidata.org URL.
 *
 * <p>Rendered as a collapsed "source: Wikidata" chip on the owning card;
 * expanding it reveals the QID and a clickable link. The QID remains the
 * owner's canonical identity — this object just groups the provenance fields
 * so they stop cluttering the card's top level.
 */
public class WikidataSource extends Source {

    public WikidataSource(String qid) {
        this(qid, qid == null || qid.isBlank() ? ""
                : "https://www.wikidata.org/wiki/" + qid.strip());
    }

    public WikidataSource(String qid, String wikidataUrl) {
        super("Wikidata", qid, wikidataUrl);
    }
}
