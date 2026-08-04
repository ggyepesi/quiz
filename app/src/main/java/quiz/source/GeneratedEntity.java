package quiz.source;

import objectview.ViewableAdapter;
import objectview.annotations.Hidden;
import objectview.annotations.Provenance;

/**
 * Base for code-generated domain entities (the runtime-compiled classes the
 * ModelBuilder emits).
 *
 * <p>Identity is the stable {@code identifier} the mapper assigns at creation —
 * NEVER derived from the anchor. The {@code anchor} is the transient enrichment
 * handle ("where to fetch more data"), independent of identity, so the two
 * carriers ({@code WikidataDynamicObject} and this) are symmetric: stable
 * identity + transient enrichment anchor.</p>
 */
public abstract class GeneratedEntity extends ViewableAdapter implements Sourced {

    @Hidden
    private String identifier = "";
    @Hidden
    private String label = "";

    // Transient enrichment/provenance handle — where to fetch more data. Not identity.
    @Provenance
    private Source anchor;

    @Override public String getIdentifier() { return identifier; }

    @Override public String getDisplayName() {
        return label == null || label.isBlank() ? identifier : label;
    }

    public void identifier(String identifier) {
        this.identifier = identifier == null ? "" : identifier;
    }

    public void label(String label) { this.label = label == null ? "" : label; }

    @Override public Source anchor() { return anchor; }

    @Override public void anchor(Source anchor) { this.anchor = anchor; }
}
