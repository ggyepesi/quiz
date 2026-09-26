package wikidata.explore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The cached P1696 column has one reader, so every consumer pairs relations alike. */
class WikidataPropertyStoreTest {

    @TempDir File directory;

    @Test void inversePropertiesAreNormalizedOnceByTheCatalogueOwner() throws Exception {
        WikidataPropertyStore store = new WikidataPropertyStore(
                new File(directory, "properties.tsv"));
        store.write(List.of(
                new WikidataProperty("p1365", "replaces", "", "WikibaseItem", "AUTO",
                        "", " p1366,P155 "),
                new WikidataProperty("P279", "subclass of", "", "WikibaseItem", "AUTO",
                        "", "")));

        var inverses = store.inverseProperties();
        assertEquals(Set.of("P1366", "P155"), inverses.get("P1365"));
        assertEquals(false, inverses.containsKey("P279"),
                "a property with no stated converse remains a single-field relation");
    }
}
