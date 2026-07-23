package quiz.transform.app;

import objectview.media.ImagePane;
import objectview.utils.swing.CachedImage;
import org.junit.jupiter.api.Test;
import quiz.QuizableAdapter;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.awt.GraphicsEnvironment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Saving a hand-written domain that holds live Swing {@link ImagePane} fields (State,
 * NobelPrize, …): {@link QuizableToWdo} must persist those as serializable
 * {@link WikidataMediaValue}s (label + source url + svg) instead of raw panels — which
 * previously blew up Jackson with "ImagePane not BeanSerializable".
 */
class QuizableToWdoMediaTest {

    static {
        // Lazy so ImagePane construction doesn't render; it still needs a display for
        // its static JFrame, so the test itself skips when truly headless (see below).
        System.setProperty("objectview.lazyImages", "true");
    }

    /** A minimal hand-written Quizable with one image field and a list of images. */
    static final class Flag extends QuizableAdapter {
        private final ImagePane portrait;
        private final List<ImagePane> versions;

        Flag(ImagePane portrait, List<ImagePane> versions) {
            this.portrait = portrait;
            this.versions = versions;
        }

        @Override public String getIdentifier() { return "F1"; }
        @Override public String getDisplayName() { return "Germany"; }
    }

    private static ImagePane pane(String title, String source, boolean svg) throws Exception {
        // filename-source (Nobel file: URL) when not svg; url-source (Commons) for svg.
        CachedImage image = svg
                ? new CachedImage(null, source, true)
                : new CachedImage(source, null, false);
        return new ImagePane(title, null, image, false);
    }

    @Test void imagePanesBecomeSerializableMediaValues() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(),
                "ImagePane's static JFrame needs a display");
        ImagePane portrait = pane("Portrait", "file:/nobel/portraits/germany.jpg", false);
        ImagePane version = pane("Flag", "https://commons.example/germany.svg", true);

        List<WikidataDynamicObject> pool =
                QuizableToWdo.pool(List.of(new Flag(portrait, List.of(version))));

        assertEquals(1, pool.size());
        WikidataDynamicObject wdo = pool.get(0);

        Object p = wdo.get("portrait");
        assertInstanceOf(WikidataMediaValue.class, p, "portrait should convert to a media value");
        WikidataMediaValue pv = (WikidataMediaValue) p;
        assertEquals("file:/nobel/portraits/germany.jpg", pv.url());
        assertEquals("Portrait", pv.label());
        assertFalse(pv.svg());

        Object versions = wdo.get("versions");
        assertInstanceOf(List.class, versions);
        Object first = ((List<?>) versions).get(0);
        assertInstanceOf(WikidataMediaValue.class, first, "list images should convert too");
        WikidataMediaValue fv = (WikidataMediaValue) first;
        assertEquals("https://commons.example/germany.svg", fv.url());
        assertTrue(fv.svg());

        // No live Swing component may survive into the pool.
        assertFalse(wdo.dynamicFieldValues().values().stream()
                        .anyMatch(v -> v instanceof ImagePane),
                "no ImagePane may remain in the persisted pool");
    }
}
