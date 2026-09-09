package objectview;

import objectview.field.ViewableFieldPaths;
import objectview.search.SearchAndSort;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Search/sort over DYNAMIC (map-held) types obeys the config: a config-named
 * dynamic field yields a search path (no declared Java field needed), extraction
 * reads the property map, and identity (name) is only implied by "all fields" —
 * an explicit config means exactly what it names.
 */
class DynamicSearchConfigTest {

    private static DynamicViewable nomination(String qid, String name, boolean won) {
        DynamicViewable q = new DynamicViewable(qid, name);
        q.type("Nomination");
        q.put("won", won);
        return q;
    }

    private static ViewConfig explicit(String... fieldNames) {
        ViewConfig cfg = ViewConfig.of(DynamicViewable.class);
        cfg.setAllFields(false);
        for (String f : fieldNames) {
            cfg.addField(f, ViewConfig.leaf());
        }
        return cfg;
    }

    private static List<ViewableFieldPaths.PathInfo> paths(
            Viewable sample, ViewConfig config) {
        return ViewableFieldPaths.collectFromSample(
                sample, config, ViewableFieldPaths.NOT_MEDIA_FIELDS);
    }

    @Test void configNamedDynamicFieldYieldsAPath() {
        List<ViewableFieldPaths.PathInfo> paths =
                ViewableFieldPaths.collect(explicit("won"));
        assertTrue(paths.stream().anyMatch(p -> p.dotted().equals("won")),
                paths.toString());
        // name was NOT named and allFields is off — it must not sneak in.
        assertFalse(paths.stream().anyMatch(p -> p.dotted().equals("name")),
                paths.toString());
    }

    @Test void searchMatchesTheConfiguredDynamicFieldOnly() {
        List<Viewable> pool = List.of(
                nomination("N1", "Casablanca", true),
                nomination("N2", "Citizen Kane", false));

        SearchAndSort engine = new SearchAndSort();

        // won checked, name not: "casablanca" finds nothing, "true" hits won.
        var byName = engine.searchViewablesByPath(
                pool, List.of("casablanca"), paths(pool.getFirst(), explicit("won")), false);
        assertTrue(byName.isEmpty(), byName.toString());

        var byWon = engine.searchViewablesByPath(
                pool, List.of("true"), paths(pool.getFirst(), explicit("won")), false);
        assertEquals(1, hitsLabelled(byWon, "won").size(), byWon.toString());

        // name checked: the display name matches again.
        String displayKey = objectview.field.ViewableContractFieldSet.DISPLAY_KEY;
        String displayLabel = objectview.field.ViewableContractFieldSet.label(displayKey);
        var withName = engine.searchViewablesByPath(
                pool, List.of("casablanca"),
                paths(pool.getFirst(), explicit(displayKey)), false);
        assertEquals(1, hitsLabelled(withName, displayLabel).size(),
                withName.toString());
    }

    /** Hits shown under one label. Search is identified by field path, because two
     *  fields can be shown the same label; this test's labels are unambiguous. */
    private static List<Viewable> hitsLabelled(
            Map<ViewableFieldPaths.PathInfo, List<Viewable>> hits, String label) {
        return hits.entrySet().stream()
                .filter(hit -> label.equals(hit.getKey().title()))
                .flatMap(hit -> hit.getValue().stream())
                .toList();
    }

    @Test void sortsByADynamicField() {
        DynamicViewable a = nomination("N1", "A", false);
        DynamicViewable b = nomination("N2", "B", false);
        a.put("year", 2001);
        b.put("year", 1999);

        List<ViewableFieldPaths.PathInfo> sortPaths =
                ViewableFieldPaths.collect(explicit("year"));
        List<Viewable> sorted = new SearchAndSort()
                .sortViewables(List.of(a, b), sortPaths);

        assertEquals("B", sorted.get(0).getDisplayName());   // 1999 first
    }
}
