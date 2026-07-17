package objectview;

import objectview.ViewablePanelSearchAndSort;
import objectview.field.ViewableFieldPaths;
import objectview.viewconfig.ViewablePanelConfig;
import org.junit.jupiter.api.Test;
import objectview.Viewable;
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

    private static ViewablePanelConfig explicit(String... fieldNames) {
        ViewablePanelConfig cfg = ViewablePanelConfig.of(DynamicQuizable.class);
        cfg.setAllFields(false);
        for (String f : fieldNames) {
            cfg.addField(f, ViewablePanelConfig.leaf());
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

        ViewablePanelSearchAndSort engine = new ViewablePanelSearchAndSort();

        // won checked, name not: "casablanca" finds nothing, "true" hits won.
        Map<String, List<Viewable>> byName = engine.searchQuizables(
                pool, List.of("casablanca"), explicit("won"));
        assertTrue(byName.isEmpty(), byName.toString());

        Map<String, List<Viewable>> byWon = engine.searchQuizables(
                pool, List.of("true"), explicit("won"));
        assertEquals(1, byWon.getOrDefault("won", List.of()).size(), byWon.toString());

        // name checked: the display name matches again.
        Map<String, List<Viewable>> withName = engine.searchQuizables(
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
        List<Viewable> sorted = new ViewablePanelSearchAndSort()
                .sortQuizables(List.of(a, b), sortPaths);

        assertEquals("B", sorted.get(0).getDisplayName());   // 1999 first
    }
}
