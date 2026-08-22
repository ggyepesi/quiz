package wikidata.explore.workbench;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.media.MediaValue;
import wikidata.explore.CommonsMedia;
import wikidata.explore.extract.WikidataMediaValue;
import wikidata.explore.query.result.DiscoveredProperty;
import wikidata.ui.WikidataLinks;

/**
 * One discovered property, as a Viewable.
 *
 * <p>Discovery's results were a bespoke {@code JTable}, which is why they had no search:
 * a table built for one screen re-implements what the shared view already does — search,
 * field config, links, selection — and drifts in look from every other list in the app.
 * As a Viewable the rows go through the same machinery as everything else, and the
 * row ACTIONS move to buttons over the selection, which is what a table's per-row
 * buttons were bought for.
 *
 * <p>The fields below ARE the columns: what the view shows is declared here, once, and
 * every field it can reach is view data. Anything the row merely carries — the source
 * record — is {@code @Hidden}, or it renders as a cell repeating the whole row.
 * The identifiers stay bare (P569, Q72717): a
 * {@link WikidataLinks#valueLinker() value linker} makes them clickable at render time,
 * so the link is not a second, near-duplicate column.
 */
public class DiscoveredPropertyViewable extends ViewableAdapter {

    /** The row's source. NOT view data: it holds every value the columns show one by
     *  one, so rendered it would repeat the entire row inside a single cell. */
    @Hidden
    private final DiscoveredProperty property;

    @DisplayField
    private final String name;
    private final String pid;
    private final String direction;
    private final String holds;
    private final String type;
    private final String frequency;
    private final String example;
    private final MediaValue exampleImage;
    private final String exampleQid;

    public DiscoveredPropertyViewable(DiscoveredProperty property) {
        this.property = property;
        this.name = property.label() == null || property.label().isBlank()
                ? property.pid() : property.label();
        this.pid = property.pid();
        this.direction = property.direction();
        this.holds = property.kind() == null ? "" : property.kind().name();
        this.type = property.typeLabel() == null ? "" : property.typeLabel();
        this.frequency = property.frequency();

        // What an example IS follows the property's kind, so exactly one of these
        // carries it: an image property's example is a picture (a file name is not
        // what it shows), everything else's is text.
        this.exampleImage = image(property);
        this.example = exampleImage != null ? ""
                : property.exampleDisplay() == null ? "" : property.exampleDisplay();
        // The QID only when it is not already what `example` reads — an example with no
        // label displays AS its QID, and repeating it made this column look like a copy
        // of the one beside it. Bare, so the value linker makes it clickable.
        String qid = property.exampleQid() == null ? "" : property.exampleQid();
        this.exampleQid = qid.equals(example) ? "" : qid;
    }

    /** A media example rendered as the image it is. The value carries the file's URL,
     *  which is why the raw value is kept: the display string is only its file name. */
    private static MediaValue image(DiscoveredProperty property) {
        if (property.kind() != DiscoveredProperty.PropertyKind.MEDIA) {
            return null;
        }
        String value = property.exampleValue() == null || property.exampleValue().isBlank()
                ? property.exampleDisplay() : property.exampleValue();
        if (value == null || value.isBlank()) {
            return null;
        }
        return new WikidataMediaValue(
                CommonsMedia.fileName(value), CommonsMedia.filePathUrl(value),
                CommonsMedia.isSvg(value));
    }

    public DiscoveredProperty property() { return property; }

    public boolean hasImage() { return exampleImage != null; }

    public boolean hasExampleQid() { return !exampleQid.isBlank(); }

    @Override public String getIdentifier() { return property.pid(); }

    @Override public String getDisplayName() { return name; }

    @Override public String toString() { return name + " (" + property.pid() + ")"; }
}
