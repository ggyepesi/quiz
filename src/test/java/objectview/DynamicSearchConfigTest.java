package objectview;

import objectview.field.ViewableFieldPaths;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import quiz.transform.DynamicQuizable;

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

    private static DynamicQuizable nomination(String qid, String name, boolean won) {
        DynamicQuizable q = new DynamicQuizable(qid, name);
        q.type("Nomination");
        q.put("won", won);
        return q;
    }

    private static ViewConfig explicit(String... fieldNames) {
        ViewConfig cfg = ViewConfig.of(DynamicQuizable.class);
        cfg.setAllFields(false);
        for (String f : fieldNames) {
            cfg.addField(f, ViewConfig.leaf());
        }
        return cfg;
    }

    @Test void configNamedDynamicFieldYieldsAPath() {
        List<ViewableFieldPaths.FieldPath> paths =
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
        Map<String, List<Viewable>> byName = engine.searchViewables(
                pool, List.of("casablanca"), explicit("won"));
        assertTrue(byName.isEmpty(), byName.toString());

        Map<String, List<Viewable>> byWon = engine.searchViewables(
                pool, List.of("true"), explicit("won"));
        assertEquals(1, byWon.getOrDefault("won", List.of()).size(), byWon.toString());

        // name checked: the display name matches again.
        Map<String, List<Viewable>> withName = engine.searchViewables(
                pool, List.of("casablanca"), explicit("name"));
        assertEquals(1, withName.getOrDefault("name", List.of()).size(),
                withName.toString());
    }

    @Test void sortsByADynamicField() {
        DynamicQuizable a = nomination("N1", "A", false);
        DynamicQuizable b = nomination("N2", "B", false);
        a.put("year", 2001);
        b.put("year", 1999);

        List<ViewableFieldPaths.FieldPath> sortPaths =
                ViewableFieldPaths.collect(explicit("year"));
        List<Viewable> sorted = new SearchAndSort()
                .sortViewables(List.of(a, b), sortPaths);

        assertEquals("B", sorted.get(0).getDisplayName());   // 1999 first
    }
}
