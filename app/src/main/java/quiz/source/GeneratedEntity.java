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

    @Override public String getIdentifier() { return identifier; }

    @Override public String getDisplayName() {
        return label == null || label.isBlank() ? identifier : label;
    }

    public void identifier(String identifier) {
        this.identifier = identifier == null ? "" : identifier;
    }

    public void label(String label) { this.label = label == null ? "" : label; }
}
