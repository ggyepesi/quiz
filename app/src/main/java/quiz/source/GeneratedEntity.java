package quiz.source;

import objectview.ViewableAdapter;
import objectview.annotations.Hidden;

/**
 * Base for code-generated domain entities (the runtime-compiled classes the
 * ModelBuilder emits).
 *
 * <p>Identity is the stable {@code identifier} the mapper assigns at creation.
 * The instance holds only results; where it came from (its originating source)
 * is curation history, not a field on the entity.</p>
 */
public abstract class GeneratedEntity extends ViewableAdapter {

    @Hidden
    private String identifier = "";
    @Hidden
    private String label = "";
    @Hidden
    private boolean part;
    @Hidden
    private java.util.List<String> sourceIdentities = new java.util.ArrayList<>();

    @Override public String getIdentifier() { return identifier; }

    @Override public String getDisplayName() {
        return label == null || label.isBlank() ? identifier : label;
    }

    public void identifier(String identifier) {
        this.identifier = identifier == null ? "" : identifier;
    }

    public void label(String label) { this.label = label == null ? "" : label; }

    /** See {@link objectview.Viewable#isPart()} — carried from the source object so a
     *  rendered part behaves the same whichever pool it came from. */
    @Override public boolean isPart() { return part; }

    public void part(boolean value) { this.part = value; }

    /** Provider-qualified identities retained when several source candidates become
     * one modeled instance. They are acquisition/provenance metadata, not model fields. */
    public java.util.List<String> sourceIdentities() {
        return java.util.List.copyOf(sourceIdentities);
    }

    public void sourceIdentities(java.util.Collection<String> values) {
        sourceIdentities.clear();
        if (values != null) values.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(value -> !value.isBlank()).distinct()
                .sorted().forEach(sourceIdentities::add);
    }

    /** Keep the ordinary Wikidata link when a content-keyed instance retains one or
     * more Wikidata source identities instead of using a QID as modeled identity. */
    public String getUrl() {
        return sourceIdentities.stream().filter(value -> value.startsWith("wikidata:Q"))
                .map(value -> "https://www.wikidata.org/wiki/" + value.substring(9))
                .findFirst().orElse("");
    }
}
