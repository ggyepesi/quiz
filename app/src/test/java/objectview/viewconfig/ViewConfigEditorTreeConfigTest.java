package objectview.viewconfig;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicQuizable;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tree-mode {@link ViewConfigEditor} serialization ({@code buildTreeConfig}) and
 * row-source switching. Covers three fixed defects:
 * <ol>
 *   <li>tree serialization dropped a reference's explicit / nested-editor config;</li>
 *   <li>an EXPLICIT EMPTY nested config was rewritten to an all-fields config;</li>
 *   <li>switching the row source left {@code treeMode} + the columns stale.</li>
 * </ol>
 * The dynamic sample gives a real nested reference (nomination.category) with its own
 * child fields, so the tree recurses and round-trips.
 */
class ViewConfigEditorTreeConfigTest {

    /** nomination → category(reference) with year + winner child fields. */
    private static DynamicQuizable nominationSample() {
        DynamicQuizable category = new DynamicQuizable("Q1", "Best Picture");
        category.type("Category");
        category.put("year", 1995);
        category.put("winner", true);

        DynamicQuizable nomination = new DynamicQuizable("N1", "A Nomination");
        nomination.type("Nomination");
        nomination.put("category", category);
        return nomination;
    }

    private static ViewConfig topConfig() {
        ViewConfig config = new ViewConfig();
        config.setCls(DynamicQuizable.class);
        config.setAllFields(false);
        return config;
    }

    private static ViewConfig nestedConfig() {
        ViewConfig cfg = new ViewConfig();
        cfg.setCls(DynamicQuizable.class);
        cfg.setAllFields(false);
        return cfg;
    }

    // Bug 2: a checked reference whose nested config is EXPLICITLY EMPTY must survive
    // the round-trip as empty — not be reinflated to an all-fields config.
    @Test void explicitEmptyNestedConfigStaysEmpty() {
        ViewConfig config = topConfig();
        config.addField("category", nestedConfig());   // present but empty (allFields=false)

        ViewConfig result =
                new ViewConfigEditor(config, nominationSample()).getConfig();

        ViewConfig category = result.getFieldConfig("category");
        assertNotNull(category, "category should be present");
        assertFalse(category.isAllFields(),
                "an explicit empty nested config must not become all-fields");
        assertTrue(category.getFields().isEmpty(),
                "an explicit empty nested config must stay empty, was " + category.getFields().keySet());
    }

    // Bug 1: a saved nested SUBSET (only some child fields) must round-trip exactly —
    // the selected child kept, the unselected child dropped.
    @Test void savedNestedSubsetRoundTrips() {
        ViewConfig config = topConfig();
        ViewConfig category = nestedConfig();
        category.addField("year", ViewConfig.leaf());   // year only, not winner
        config.addField("category", category);

        ViewConfig result =
                new ViewConfigEditor(config, nominationSample()).getConfig();

        ViewConfig resultCategory = result.getFieldConfig("category");
        assertNotNull(resultCategory);
        assertTrue(resultCategory.getFields().containsKey("year"),
                "selected nested field must be kept");
        assertFalse(resultCategory.getFields().containsKey("winner"),
                "unselected nested field must not appear");
    }

    // Bug 1 (metadata): reference-level metadata (thumb / answerType / display flags)
    // must survive even when the reference also has checked children.
    @Test void nestedMetadataSurvivesWhenChildrenExist() {
        ViewConfig config = topConfig();
        ViewConfig category = nestedConfig();
        category.setThumb(true);                          // metadata on the reference
        category.addField("year", ViewConfig.leaf());     // AND a checked child
        config.addField("category", category);

        ViewConfig result =
                new ViewConfigEditor(config, nominationSample()).getConfig();

        ViewConfig resultCategory = result.getFieldConfig("category");
        assertNotNull(resultCategory);
        assertTrue(resultCategory.getFields().containsKey("year"),
                "the checked child must be kept");
        assertTrue(resultCategory.isThumb(),
                "reference-level metadata must survive alongside children");
    }

    // Bug 2 (minor fields): switching in a new config must refresh the "All minor
    // fields" state, not leave it seeded from the config present at construction.
    @Test void switchRefreshesAllMinorFieldsFromNewConfig() {
        ViewConfigEditor editor =
                new ViewConfigEditor(topConfig(), (objectview.Viewable) null);  // all-minor off
        assertFalse(editor.getConfig().isAllMinorFields());

        ViewConfig withMinor = topConfig();
        withMinor.setAllMinorFields(true);
        editor.setConfigRows(withMinor, null, null, Set.of());

        assertTrue(editor.getConfig().isAllMinorFields(),
                "the all-minor-fields state must refresh from the switched-in config");
    }

    // Bug 2 (visibility): the "All minor fields" bar must appear / disappear as a
    // switch changes whether it applies (a dynamic sample has no minor-fields notion).
    @Test void minorFieldsBarVisibilityFollowsSource() {
        // dynamic sample -> minor-fields bar not applicable -> hidden
        ViewConfigEditor editor = new ViewConfigEditor(topConfig(), nominationSample());
        assertFalse(editor.minorFieldsBarVisible(),
                "a dynamic sample has no minor-fields bar");

        // switch to a reflected (null) sample -> bar applies -> visible
        editor.setConfigRows(topConfig(), null, null, Set.of());
        assertTrue(editor.minorFieldsBarVisible(),
                "the bar must appear once the source makes it applicable");
    }

    // Bug 3: an editor created as a flat path table must switch cleanly to tree mode
    // (and rebuild its columns/model) when handed config rows.
    @Test void switchingRowSourceEntersTreeMode() {
        ViewConfigEditor editor =
                new ViewConfigEditor(FieldTableContributor.DEFAULT);   // path (flat) source
        assertFalse(editor.inTreeMode(), "a path source is flat");

        ViewConfig config = topConfig();
        config.addField("category", nestedConfig());   // category checked
        editor.setConfigRows(config, nominationSample(), null, Set.of());

        assertTrue(editor.inTreeMode(),
                "switching to a config source must enter tree mode");
        // Proves the model rebuilt as a tree (nested reference enumerated + serialized),
        // not a stale flat table.
        assertNotNull(editor.getConfig().getFieldConfig("category"),
                "the checked reference must serialize after the row-source switch");
    }
}
