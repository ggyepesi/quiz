package quiz.source;

import objectview.ViewableAdapter;
import objectview.annotations.Hidden;
import objectview.annotations.Minor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    /** Wikidata "Also known as" values. Minor keeps large alias sets out of compact
     * cards while leaving them available to view configuration, search and sort. */
    @Minor
    private List<String> alternateNames = new ArrayList<>();

    public List<String> alternateNames() { return alternateNames; }


    @Override public String getIdentifier() { return identifier; }

    @Override public String getDisplayName() {
        return label == null || label.isBlank() ? identifier : label;
    }

    public void identifier(String identifier) {
        this.identifier = identifier == null ? "" : identifier;
    }

    public void label(String label) { this.label = label == null ? "" : label; }

    public void alternateNames(Collection<String> values) {
        alternateNames.clear();
        if (values != null) alternateNames.addAll(values);
    }

    /** See {@link objectview.Viewable#isPart()} — carried from the source object so a
     *  rendered part behaves the same whichever pool it came from. */
    @Override public boolean isPart() { return part; }

    public void part(boolean value) { this.part = value; }
}
