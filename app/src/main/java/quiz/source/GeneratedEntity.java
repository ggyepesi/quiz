package quiz.source;

import objectview.ViewableAdapter;
import objectview.annotations.Hidden;
import objectview.annotations.Provenance;

/**
 * Base for code-generated domain entities (the runtime-compiled classes the
 * ModelBuilder emits).
 *
 * <p>Identity comes from the provenance {@code anchor} the mapper attaches: a
 * native Wikidata entity is identified by its QID, a reified statement by its
 * GUID. The anchor is a field (named {@code anchor}, not {@code source}), so a
 * generated entity is {@link Anchorable} like every other carrier and never
 * extends a source descriptor.</p>
 */
public abstract class GeneratedEntity extends ViewableAdapter implements Anchorable {

    @Provenance
    private SourceViewable anchor;
    @Hidden
    private String label = "";

    @Override public String getIdentifier() {
        return anchor == null ? "" : anchor.id();
    }

    @Override public String getDisplayName() {
        if (label != null && !label.isBlank()) return label;
        return anchor == null ? "" : anchor.getDisplayName();
    }

    public void label(String label) { this.label = label == null ? "" : label; }

    @Override public SourceViewable anchor() { return anchor; }

    @Override public void anchor(SourceViewable anchor) { this.anchor = anchor; }
}
