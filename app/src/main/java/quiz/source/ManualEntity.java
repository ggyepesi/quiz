package quiz.source;

import objectview.ViewableAdapter;
import objectview.annotations.Provenance;

/**
 * Base for hand-authored domain entities (a {@code State}, a {@code Laureate}, …).
 *
 * <p>Provides the uniform provenance {@code anchor}: <b>manual by default</b>
 * (derived from the entity's own identity), and re-anchorable to a
 * {@link WikidataViewable} etc. without changing the entity's identity — the same
 * contract the dynamic carrier honours. The anchor is a field named {@code anchor},
 * not {@code source}, to avoid colliding with reify/structural {@code source}
 * fields.</p>
 */
public abstract class ManualEntity extends ViewableAdapter implements Anchorable {

    @Provenance
    private SourceViewable anchor;

    @Override public SourceViewable anchor() {
        if (anchor == null) {
            anchor = new ManualViewable(getIdentifier(), getDisplayName());
        }
        return anchor;
    }

    @Override public void anchor(SourceViewable anchor) { this.anchor = anchor; }
}
