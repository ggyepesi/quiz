package quiz.source;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.Hidden;
import objectview.annotations.Link;

/**
 * A Wikidata provenance descriptor: the owning object's data comes from the
 * Wikidata entity identified by {@link #qid()}.
 *
 * <p>This is the <b>value of an {@code anchor} field</b>, not a base class — a
 * domain object <em>has</em> a {@code WikidataViewable}, it does not extend one.
 * It is immutable: to re-anchor an object you set its {@code source} to a new
 * descriptor, which establishes the object's new source identity.</p>
 */
public final class WikidataViewable extends ViewableAdapter implements SourceViewable {

    @Hidden
    private final String qid;
    @Hidden
    private final String name;
    @Hidden
    private final String wikidataUrl;
    @Link
    private final String identity;

    @JsonCreator
    public WikidataViewable(
            @JsonProperty("qid") String qid,
            @JsonProperty("name") String name) {
        this.qid = qid == null ? "" : qid.strip();
        this.name = name == null || name.isBlank() ? this.qid : name;
        // A Wikidata source's id is a QID by definition; only a real QID yields a
        // link. A blank/non-QID here is a construction error, not a silent fallback.
        this.wikidataUrl = this.qid.matches("Q\\d+")
                ? "https://www.wikidata.org/wiki/" + this.qid : "";
        this.identity = wikidataUrl.isBlank() ? "" : this.name + "|" + wikidataUrl;
    }

    public WikidataViewable(String qid) { this(qid, qid); }

    @Override public String provenance() { return "wikidata"; }

    /** A Wikidata source's native id <em>is</em> the QID. */
    @Override public String id() { return qid; }

    public String qid() { return qid; }

    public String wikidataUrl() { return wikidataUrl; }

    @Override public String getIdentifier() { return qid; }

    @Override public String getDisplayName() {
        return name == null || name.isBlank() ? qid : name;
    }
}
