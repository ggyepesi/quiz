package objectview.viewconfig;

import objectview.field.RecordTypes;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config editor enumerates a DYNAMIC sample's map-held fields (WDO /
 * DynamicViewable) — the field panel now works over dynamic domains, not just
 * reflected ones.
 */
class ViewConfigEditorDynamicTest {
    static {
        // The fixture records' types, declared as a producer declares them (#363).
        RecordTypes.declare("Category", RecordTypes.number("year"), RecordTypes.value("winner"));
        RecordTypes.declare("Nomination", RecordTypes.number("year"),
                RecordTypes.record("category", "Category"), RecordTypes.text("note"));
        RecordTypes.declare("Motivation", RecordTypes.text("action"));
        RecordTypes.declare("Laureate", RecordTypes.value("portrait"));
        RecordTypes.declare("LaureatesWithMotivation",
                RecordTypes.records("laureates", "Laureate"),
                RecordTypes.record("motivation", "Motivation"));
        RecordTypes.declare("NobelPrize",
                RecordTypes.records("laureatesWithMotivation", "LaureatesWithMotivation"));
    }



    @Test void enumeratesDynamicSampleFields() {
        DynamicViewable category = new DynamicViewable("Q1", "Best Picture");
        category.type("Category");

        DynamicViewable nomination = new DynamicViewable("N1", "A Nomination");
        nomination.type("Nomination");
        nomination.put("year", 2000);
        nomination.put("category", category);

        ViewConfig config = ViewConfig.of(DynamicViewable.class);
        ViewConfigEditor editor = new ViewConfigEditor(config, nomination);

        ViewConfig result = editor.getConfig();
        assertTrue(result.getFields().containsKey("year"), result.getFields().keySet().toString());
        assertTrue(result.getFields().containsKey("category"), result.getFields().keySet().toString());
    }
}
