package quiz.source;

import quiz.QuizableAdapter;
import quiz.annotations.Link;

/**
 * {@link Source} backed by Wikidata: a QID plus its wikidata.org URL.
 *
 * <p>Rendered as a collapsed "source: Wikidata" chip on the owning card;
 * expanding it reveals the QID and a clickable link. The QID remains the
 * owner's canonical identity — this object just groups the provenance fields
 * so they stop cluttering the card's top level.
 */
public class WikidataSource extends QuizableAdapter implements Source {

    private String qid;

    @Link
    private String wikidataUrl;

    public WikidataSource() {
        this("");
    }

    public WikidataSource(String qid) {
        this.qid = qid == null ? "" : qid.strip();
        this.wikidataUrl = this.qid.isBlank()
                ? ""
                : "https://www.wikidata.org/wiki/" + this.qid;
    }

    public WikidataSource(String qid, String wikidataUrl) {
        this.qid = qid == null ? "" : qid.strip();
        this.wikidataUrl = wikidataUrl == null ? "" : wikidataUrl;
    }

    @Override
    public String sourceId() {
        return qid;
    }

    @Override
    public String url() {
        return wikidataUrl;
    }

    @Override
    public String kind() {
        return "Wikidata";
    }

    @Override
    public String getIdentifier() {
        return qid;
    }

    @Override
    public String getDisplayName() {
        // The chip reads "source: Wikidata"; the QID/URL are the expanded body.
        return kind();
    }

    @Override
    public String typeName() {
        return "Source";
    }

    @Override
    public String toString() {
        return kind() + (qid == null || qid.isBlank() ? "" : " (" + qid + ")");
    }
}
