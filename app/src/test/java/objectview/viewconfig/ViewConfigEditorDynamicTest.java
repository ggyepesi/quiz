package objectview.viewconfig;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicQuizable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config editor enumerates a DYNAMIC sample's map-held fields (WDO /
 * DynamicQuizable) — the field panel now works over dynamic domains, not just
 * reflected ones.
 */
class ViewConfigEditorDynamicTest {

    @Test void enumeratesDynamicSampleFields() {
        DynamicQuizable category = new DynamicQuizable("Q1", "Best Picture");
        category.type("Category");

        DynamicQuizable nomination = new DynamicQuizable("N1", "A Nomination");
        nomination.type("Nomination");
        nomination.put("year", 2000);
        nomination.put("category", category);

        ViewConfig config = ViewConfig.all(DynamicQuizable.class);
        ViewConfigEditor editor = new ViewConfigEditor(config, nomination);

        ViewConfig result = editor.getConfig();
        assertTrue(result.getFields().containsKey("year"), result.getFields().keySet().toString());
        assertTrue(result.getFields().containsKey("category"), result.getFields().keySet().toString());
    }
}
