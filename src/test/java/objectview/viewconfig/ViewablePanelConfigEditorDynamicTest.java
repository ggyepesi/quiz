package objectview.viewconfig;

import objectview.viewconfig.ViewablePanelConfig;
import objectview.viewconfig.ViewablePanelConfigEditor;
import org.junit.jupiter.api.Test;
import quiz.transform.DynamicQuizable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config editor enumerates a DYNAMIC sample's map-held fields (WDO /
 * DynamicQuizable) — the field panel now works over dynamic domains, not just
 * reflected ones.
 */
class ViewablePanelConfigEditorDynamicTest {

    @Test void enumeratesDynamicSampleFields() {
        DynamicQuizable category = new DynamicQuizable("Q1", "Best Picture");
        category.type("Category");

        DynamicQuizable nomination = new DynamicQuizable("N1", "A Nomination");
        nomination.type("Nomination");
        nomination.put("year", 2000);
        nomination.put("category", category);

        ViewablePanelConfig config = ViewablePanelConfig.all(DynamicQuizable.class);
        ViewablePanelConfigEditor editor = new ViewablePanelConfigEditor(config, nomination);

        ViewablePanelConfig result = editor.getConfig();
        assertTrue(result.getFields().containsKey("year"), result.getFields().keySet().toString());
        assertTrue(result.getFields().containsKey("category"), result.getFields().keySet().toString());
    }
}
