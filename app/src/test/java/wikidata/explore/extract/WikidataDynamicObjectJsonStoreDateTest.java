package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import wikidata.explore.extract.WikidataDynamicObject;

import aux.FlexibleDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Typed dates survive the snapshot: a FlexibleDate field round-trips at its
 * precision (via the DateVal marker). Snapshots are regenerated rather than
 * migrated, so there is no raw-literal upgrade-on-load path to cover.
 */
class WikidataDynamicObjectJsonStoreDateTest {

    @TempDir File dir;

    @Test void flexibleDateRoundTripsAtPrecision() throws Exception {
        WikidataDynamicObject o = new WikidataDynamicObject("Q1", "Casablanca");
        o.type("Film");   // a member root is a stamped entity (v5 roots are typed)
        o.put("released", new FlexibleDate(1942, 11, 26));
        o.put("year", new FlexibleDate(1943));

        File file = new File(dir, "dates.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(o), file);

        WikidataDynamicObject back = store.load(file).getFirst();
        assertEquals(new FlexibleDate(1942, 11, 26),
                back.dynamicFields().get("released"));
        assertEquals(new FlexibleDate(1943), back.dynamicFields().get("year"));
    }
}
