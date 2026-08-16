package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import objectview.field.FieldRef;
import objectview.field.FieldSet;
import objectview.media.MediaValue;
import wikidata.explore.query.result.DiscoveredProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery's results had no search because they were a bespoke JTable. As Viewables
 * they go through the same search and rendering path as every other list in the app —
 * so what the row shows, and how, is declared by the Viewable rather than by a column
 * model built for one screen.
 */
class DiscoveredPropertyViewableTest {

    private static DiscoveredProperty scalar() {
        return new DiscoveredProperty("P569", "date of birth", "", "Time",
                DiscoveredProperty.PropertyKind.SCALAR, 187, 200,
                "1909-09-07", "", "1909-09-07", "outgoing");
    }

    @Test void aDiscoveredPropertyRendersAsAViewableRow() {
        DiscoveredPropertyViewable row = new DiscoveredPropertyViewable(scalar());

        assertEquals("P569", row.getIdentifier());
        assertEquals("date of birth", row.getDisplayName());

        List<String> fields = row.fields().fields().stream().map(FieldRef::name).toList();
        for (String expected :
                List.of("pid", "direction", "holds", "type", "frequency", "example")) {
            assertTrue(fields.contains(expected), expected + " missing from " + fields);
        }
        FieldSet set = row.fields();
        assertEquals("P569", set.read("pid"));
        assertEquals("outgoing", set.read("direction"));
        assertEquals("187 / 200", set.read("frequency"));
        assertEquals("1909-09-07", set.read("example"),
                "a literal value is its own example");
        assertEquals("", set.read("exampleQid"), "a literal denotes no entity");
    }

    /** An entity example reads as its label, with the QID beside it as the link and the
     *  argument the Allow/Exclude actions take. */
    @Test void anEntityExampleKeepsBothItsLabelAndItsQid() {
        DiscoveredPropertyViewable row = new DiscoveredPropertyViewable(
                new DiscoveredProperty("P1411", "nominated for", "", "Item",
                        DiscoveredProperty.PropertyKind.ENTITY, 95, 200,
                        "http://www.wikidata.org/entity/Q103360", "Q103360",
                        "Academy Award for Best Director", "incoming"));

        assertEquals("Academy Award for Best Director", row.fields().read("example"));
        assertEquals("Q103360", row.fields().read("exampleQid"),
                "bare, so the value linker turns it into a link");
    }

    /** With no label the example already READS as its QID; repeating it in the next
     *  column made that column look like a copy of the one beside it. */
    @Test void anUnlabelledEntityExampleIsNotShownTwice() {
        DiscoveredPropertyViewable row = new DiscoveredPropertyViewable(
                new DiscoveredProperty("P31", "instance of", "", "Item",
                        DiscoveredProperty.PropertyKind.ENTITY, 200, 200,
                        "http://www.wikidata.org/entity/Q5", "Q5", "Q5", "outgoing"));

        assertEquals("Q5", row.fields().read("example"));
        assertEquals("", row.fields().read("exampleQid"));
    }

    /** An image property's example is a picture. The file name is not what it shows, so
     *  the row carries the media value the renderer turns into the image. */
    @Test void aMediaExampleIsTheImageItself() {
        DiscoveredPropertyViewable row = new DiscoveredPropertyViewable(
                new DiscoveredProperty("P18", "image", "", "Commons media",
                        DiscoveredProperty.PropertyKind.MEDIA, 120, 200,
                        "http://commons.wikimedia.org/wiki/Special:FilePath/Elia%20Kazan.jpg",
                        "", "Elia Kazan.jpg", "outgoing"));

        MediaValue media = assertInstanceOf(MediaValue.class, row.fields().read("exampleImage"));
        assertEquals("Elia Kazan.jpg", media.mediaLabel());
        assertTrue(media.mediaUrl().startsWith(
                        "https://commons.wikimedia.org/wiki/Special:FilePath/"),
                "the image loads from its file URL, not from a display string: "
                        + media.mediaUrl());
        assertEquals("", row.fields().read("example"),
                "the picture IS the example — the file name beside it would repeat it");
    }

    @Test void aNonMediaPropertyHasNoImage() {
        assertNull(new DiscoveredPropertyViewable(scalar()).fields().read("exampleImage"));
    }

    /**
     * The row CARRIES the record it was built from, and every reachable field is view
     * data — so an un-hidden record renders as one cell repeating the whole row.
     */
    @Test void theSourceRecordIsNotAColumn() {
        DiscoveredPropertyViewable row = new DiscoveredPropertyViewable(scalar());

        List<String> fields = row.fields().fields().stream().map(FieldRef::name).toList();
        assertFalse(fields.contains("property"),
                "the source record is internal, not a column: " + fields);
        for (String name : fields) {
            assertFalse(row.fields().read(name) instanceof DiscoveredProperty,
                    name + " exposes the whole record as a value");
        }
    }
}
