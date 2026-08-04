package quiz.source;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.Hidden;

/**
 * A manual provenance descriptor: the owning object was assembled by a
 * hand-written loader, and its native id is a local key.
 *
 * <p>The <b>value of an {@code anchor} field</b>, not a base class — a domain
 * entity <em>has</em> a manual anchor (see {@link ManualEntity}), and gains a
 * Wikidata identity by having that field replaced with a {@link WikidataSource}.</p>
 */
public final class ManualSource extends ViewableAdapter implements Source {

    @Hidden
    private final String id;
    @Hidden
    private final String name;

    @JsonCreator
    public ManualSource(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name) {
        this.id = id == null ? "" : id;
        this.name = name == null || name.isBlank() ? this.id : name;
    }

    public ManualSource(String id) { this(id, id); }

    @Override public String provenance() { return "manual"; }

    @Override public String id() { return id; }

    @Override public String getIdentifier() { return id; }

    @Override public String getDisplayName() {
        return name == null || name.isBlank() ? id : name;
    }
}
