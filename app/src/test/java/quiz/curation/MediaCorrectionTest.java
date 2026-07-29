package quiz.curation;

import objectview.media.MediaValue;
import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaCorrectionTest {

    /** A MediaValue with the (label, url, svg) ctor shape coerce reflects against. */
    public static final class TestMedia implements MediaValue {
        private final String label;
        private final String url;
        private final boolean svg;

        public TestMedia(String label, String url, boolean svg) {
            this.label = label;
            this.url = url;
            this.svg = svg;
        }

        @Override public String mediaUrl() { return url; }
        @Override public String mediaLabel() { return label; }
        @Override public boolean mediaSvg() { return svg; }
    }

    @Test
    void urlCoercesToASingleMediaValueFromTheSample() {
        TestMedia sample = new TestMedia("old", "file:/old.jpg", false);
        Object out = Corrections.coerce(
                "https://commons.wikimedia.org/Special:FilePath/Emil_von_Behring.jpg", sample);

        TestMedia media = assertInstanceOf(TestMedia.class, out);
        assertEquals("https://commons.wikimedia.org/Special:FilePath/Emil_von_Behring.jpg",
                media.mediaUrl());
        assertEquals("Emil von Behring.jpg", media.mediaLabel());   // decoded file name
        assertFalse(media.mediaSvg());
    }

    @Test
    void urlCoercesIntoAOneElementCollectionForAMediaListField() {
        List<TestMedia> sample = new ArrayList<>();
        sample.add(new TestMedia("a flag", "file:/flag.svg", true));

        Object out = Corrections.coerce(
                "https://commons.wikimedia.org/Special:FilePath/Flag_of_X.svg", sample);

        List<?> list = assertInstanceOf(List.class, out);
        assertEquals(1, list.size());
        TestMedia media = assertInstanceOf(TestMedia.class, list.get(0));
        assertTrue(media.mediaSvg());   // .svg extension detected
        assertEquals("Flag of X.svg", media.mediaLabel());
    }

    @SuppressWarnings("unused")
    static final class Country extends ViewableAdapter {
        private final String name;
        private final List<TestMedia> flags = new ArrayList<>();

        Country(String name) { this.name = name; }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }

    @Test
    void applyFillsAnEmptyMediaCollectionFromADbpediaCorrection() {
        Country withFlag = new Country("A");
        withFlag.flags.add(new TestMedia("flag", "file:/a.svg", true));  // the shape sample
        Country missing = new Country("B");                             // empty flags = a gap
        List<Country> pool = new ArrayList<>(List.of(withFlag, missing));

        CorrectionSource dbpedia = () -> List.of(new Correction(
                "B", "flags", "https://commons.wikimedia.org/Special:FilePath/Flag_of_B.svg",
                "dbpedia"));
        int applied = Corrections.apply(pool, List.of(dbpedia));

        assertEquals(1, applied);
        assertEquals(1, missing.flags.size());                          // empty list filled
        assertEquals("Flag of B.svg", missing.flags.get(0).mediaLabel());
        assertEquals(1, withFlag.flags.size());                         // sample untouched
    }

    @Test
    void explicitMediaShapeFillsACollectionWhenNoInstanceProvidesASample() {
        Country missing = new Country("B");
        CorrectionSource dbpedia = () -> List.of(new Correction(
                missing.typeName(), "B", "flags",
                "https://example.test/Flag_of_B.svg?width=400",
                "dbpedia", Correction.MEDIA_COLLECTION));

        assertEquals(1, Corrections.apply(List.of(missing), List.of(dbpedia)));
        assertEquals(1, missing.flags.size());
        assertInstanceOf(TestMedia.class, missing.flags.get(0));
        assertTrue(missing.flags.get(0).mediaSvg());
    }

    @Test
    void explicitMediaShapeUsesTheSnapshotMediaTypeForAnEmptyDynamicField() {
        WikidataDynamicObject missing = new WikidataDynamicObject("Q1", "Missing");
        missing.type("Person");
        CorrectionSource dbpedia = () -> List.of(new Correction(
                "Person", "Q1", "image",
                "https://example.test/Portrait.svg?width=400",
                "dbpedia", Correction.MEDIA));

        assertEquals(1, Corrections.apply(List.of(missing), List.of(dbpedia)));
        WikidataMediaValue media =
                assertInstanceOf(WikidataMediaValue.class, missing.get("image"));
        assertTrue(media.mediaSvg());
    }
}
