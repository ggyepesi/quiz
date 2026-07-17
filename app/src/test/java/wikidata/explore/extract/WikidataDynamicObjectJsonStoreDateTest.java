package wikidata.explore.extract;

import aux.FlexibleDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Typed dates survive the snapshot: a FlexibleDate field round-trips at its
 * precision (via the DateVal marker), and a pre-typed-dates snapshot holding
 * the raw Wikidata time literal as a string is upgraded on load — no domain
 * regeneration needed.
 */
class WikidataDynamicObjectJsonStoreDateTest {

    @TempDir File dir;

    @Test void flexibleDateRoundTripsAtPrecision() throws Exception {
        WikidataDynamicObject o = new WikidataDynamicObject("Q1", "Casablanca");
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

    @Test void rawTimeLiteralStringUpgradesOnLoad() throws Exception {
        // An old snapshot: the date was stored as the raw literal string.
        WikidataDynamicObject o = new WikidataDynamicObject("Q1", "Old");
        o.put("born", "1875-01-01T00:00:00Z");
        o.put("plain", "not a date");

        File file = new File(dir, "legacy.json");
        WikidataDynamicObjectJsonStore store = new WikidataDynamicObjectJsonStore();
        store.save(List.of(o), file);

        WikidataDynamicObject back = store.load(file).getFirst();
        assertEquals(new FlexibleDate(1875), back.dynamicFields().get("born"));
        assertEquals("not a date", back.dynamicFields().get("plain"));
    }
}
