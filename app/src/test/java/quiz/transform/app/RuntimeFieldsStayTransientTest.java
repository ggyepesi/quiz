package quiz.transform.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Runtime fields are values a loaded model derives from retained source facts (for example
 * a Wikipedia article link). ObjectView renders, searches and sorts them through
 * {@code viewFieldValues()}, but they are not domain data: persisting them would give the
 * derived value a second, stored route that no longer follows its source. Both save paths
 * — TransformApp's Viewable conversion and the snapshot store — must leave them out.
 */
class RuntimeFieldsStayTransientTest {

    private static WikidataDynamicObject maat() {
        WikidataDynamicObject maat = new WikidataDynamicObject("Q170471", "Maat");
        maat.type("EgyptianDeity");
        maat.put("summary", "goddess of truth");
        maat.runtimeFields(Map.of("wikipediaArticle",
                "Maat|https://en.wikipedia.org/wiki/Maat"));
        return maat;
    }

    @Test void aRuntimeFieldIsVisibleButNotDomainData() {
        WikidataDynamicObject maat = maat();

        assertEquals("Maat|https://en.wikipedia.org/wiki/Maat",
                maat.fields().read("wikipediaArticle"), "ObjectView sees the runtime value");
        assertFalse(maat.dynamicFieldValues().containsKey("wikipediaArticle"));
    }

    @Test void transformSaveConversionLeavesRuntimeFieldsOut() {
        WikidataDynamicObject saved = ViewableToWdo.pool(List.of(maat())).getFirst();

        assertEquals(java.util.Set.of("summary"), saved.dynamicFieldValues().keySet());
    }

    @Test void theSnapshotStoreLeavesRuntimeFieldsOut(@TempDir Path dir) throws Exception {
        var file = dir.resolve("runtime-fields-snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().save(List.of(maat()), file);

        String json = Files.readString(file.toPath());
        assertFalse(json.contains("en.wikipedia.org/wiki/Maat"), json);
    }
}
