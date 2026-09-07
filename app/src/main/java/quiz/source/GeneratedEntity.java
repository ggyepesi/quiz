package quiz.source;

import objectview.ViewableAdapter;
import objectview.annotations.Hidden;

/**
 * Base for code-generated domain entities (the runtime-compiled classes the
 * ModelBuilder emits).
 *
 * <p>Identity is the stable {@code identifier} the mapper assigns at creation.
 * Modeled values remain separate from identity bookkeeping. Datasource-declared
 * fields, including provenance, are emitted on the generated subclass like its
 * other configured fields.</p>
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
    @Hidden
    private java.util.List<String> occurrenceIdentities = new java.util.ArrayList<>();

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

    /** Provider-qualified source occurrences retained when modeled keying combines or
     * renames source records. They identify claims or rows rather than entities. */
    public java.util.List<String> occurrenceIdentities() {
        return java.util.List.copyOf(occurrenceIdentities);
    }

    public void occurrenceIdentities(java.util.Collection<String> values) {
        occurrenceIdentities.clear();
        if (values != null) values.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(value -> !value.isBlank()).distinct()
                .sorted().forEach(occurrenceIdentities::add);
    }

    public java.util.List<WikidataStatementSource> wikidataStatementSources() {
        return SourceIdentities.wikidataStatementSources(this);
    }

    /** Keep the ordinary Wikidata link when a content-keyed instance retains one or
     * more Wikidata source identities instead of using a QID as modeled identity. */
    public String getUrl() {
        return SourceIdentities.wikidataUrl(this);
    }
}
