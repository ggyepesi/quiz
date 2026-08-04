package quiz.source;

import objectview.ViewableAdapter;
import objectview.annotations.Provenance;

/**
 * Base for hand-authored domain entities (a {@code State}, a {@code Laureate}, …).
 *
 * <p>Provides the uniform provenance {@code anchor}: <b>manual by default</b>
 * (derived from the entity's own identity), and re-anchorable to a
 * {@link WikidataSource} etc., thereby changing its source identity. The anchor
 * is a field named {@code anchor},
 * not {@code source}, to avoid colliding with reify/structural {@code source}
 * fields.</p>
 */
public abstract class ManualEntity extends ViewableAdapter implements Sourced {

    @Provenance
    private Source anchor;

    @Override public Source anchor() {
        if (anchor == null) {
            anchor = new ManualSource(getIdentifier(), getDisplayName());
        }
        return anchor;
    }

    @Override public void anchor(Source anchor) { this.anchor = anchor; }

    /** Ensure reflection sees the lazy default anchor as a value, not merely a field type. */
    @Override public objectview.field.FieldSet fields() {
        anchor();
        return objectview.field.FieldSet.of(this);
    }
}
