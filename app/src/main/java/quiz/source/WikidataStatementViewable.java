package quiz.source;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import objectview.ViewableAdapter;
import objectview.annotations.Hidden;

/**
 * A Wikidata <em>statement</em> provenance descriptor: the owning object was
 * produced from a particular statement (a GUID) on a property — not from an
 * entity.
 *
 * <p>A <b>sibling</b> of {@link WikidataViewable}, never a subclass: a statement
 * is not a QID-identified entity, so it must not inherit entity/qid semantics
 * (see the no-dynamic-carrier-inheritance rule). Both are Wikidata-sourced, but
 * they identify by different shapes — an entity by its QID, a statement by its
 * GUID + property.</p>
 */
public final class WikidataStatementViewable extends ViewableAdapter
        implements SourceViewable {

    @Hidden
    private final String statement;
    @Hidden
    private final String property;
    @Hidden
    private final String name;

    @JsonCreator
    public WikidataStatementViewable(
            @JsonProperty("statement") String statement,
            @JsonProperty("property") String property,
            @JsonProperty("name") String name) {
        this.statement = statement == null ? "" : statement;
        this.property = property == null ? "" : property;
        this.name = name == null || name.isBlank() ? this.statement : name;
    }

    @Override public String provenance() { return "wikidata"; }

    /** A statement source's native id is the statement GUID (never a QID). */
    @Override public String id() { return statement; }

    public String statement() { return statement; }

    public String property() { return property; }

    @Override public String getIdentifier() { return statement; }

    @Override public String getDisplayName() { return name; }

    @Override public String getReferenceLabel() { return getDisplayName(); }
}
