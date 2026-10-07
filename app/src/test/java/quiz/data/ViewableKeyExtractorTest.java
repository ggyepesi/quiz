package quiz.data;

import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import quiz.transform.DynamicViewable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViewableKeyExtractorTest {
    private final ViewableKeyExtractor extractor = new ViewableKeyExtractor();

    @Test
    void viewConfigPathsAndCollectionsProduceCartesianKeys() {
        ViewConfig config = selected(Item.class, "tags", "languages");
        Item item = new Item("one", List.of("t1", "t2"),
                List.of("en", "hu"), null, Map.of());

        assertEquals(List.of(
                        objectview.field.FieldPath.of("tags"),
                        objectview.field.FieldPath.of("languages")),
                extractor.paths(config));
        assertEquals(List.of(
                List.of("t1", "en"),
                List.of("t1", "hu"),
                List.of("t2", "en"),
                List.of("t2", "hu")), extractor.combinations(item, config));
    }

    @Test
    void cartesianKeysMaterializeOnlyTheRequestedCombination() {
        Item item = new Item("one",
                java.util.stream.IntStream.range(0, 1_000)
                        .mapToObj(i -> "tag-" + i).toList(),
                java.util.stream.IntStream.range(0, 1_000)
                        .mapToObj(i -> "language-" + i).toList(),
                null, Map.of());

        List<List<Object>> combinations = extractor.combinations(
                item, selected(Item.class, "tags", "languages"));

        assertInstanceOf(ViewableKeyExtractor.LazyCartesianKeys.class, combinations);
        assertEquals(1_000_000, combinations.size());
        assertEquals(List.of("tag-0", "language-0"), combinations.getFirst());
        assertEquals(List.of("tag-999", "language-999"), combinations.getLast());
    }

    @Test
    void nestedCollectionPathFansOutAcrossReferencedItems() {
        Item first = new Item("first", List.of(), List.of(), null, Map.of());
        Item second = new Item("second", List.of(), List.of(), null, Map.of());
        Item owner = new Item("owner", List.of(), List.of(), List.of(first, second), Map.of());

        assertEquals(List.of("first", "second"),
                extractor.alternatives(owner, "children.name"));
    }

    @Test
    void mapAlternativesKeepTheirKeys() {
        Item item = new Item("one", List.of(), List.of(), null,
                Map.of("home", "Budapest", "away", "Vienna"));

        List<Object> values = extractor.alternatives(item, "places");

        // Map iteration order is not part of Map.of's contract.
        assertEquals(
                java.util.Set.of("home -> Budapest", "away -> Vienna"),
                java.util.Set.copyOf(values));
    }

    @Test
    void dynamicFieldsUseTheSameFieldSetBridge() {
        DynamicViewable item = new DynamicViewable("q1", "Dynamic");
        item.put("year", 1959);

        assertEquals(1959, extractor.value(item, "year"));
        assertEquals(List.of(1959), extractor.alternatives(item, "year"));
        assertEquals("Dynamic", extractor.value(
                item, objectview.field.ViewableContractFieldSet.DISPLAY_KEY));
        assertNull(extractor.value(item, "missing"));
    }

    @Test
    void classlessSavedDomainConfigExtractsDynamicFields() {
        DynamicViewable item = new DynamicViewable("q1", "Dynamic");
        item.put("year", 1959);
        ViewConfig config = new ViewConfig();
        config.setAllFields(false);
        config.addField(
                objectview.field.ViewableContractFieldSet.DISPLAY_KEY,
                ViewConfig.leaf());
        config.addField("year", ViewConfig.leaf());

        assertEquals(List.of(List.of("Dynamic", 1959)),
                extractor.combinations(item, config));
    }

    @Test
    void missingOrEmptyConfiguredFieldRejectsTheWholeKey() {
        Item item = new Item("one", List.of(), List.of("en"), null, Map.of());

        assertEquals(List.of(), extractor.combinations(
                item, selected(Item.class, "tags", "languages")));
    }

    /** Key extraction reads literal ticks: "all fields" is rewritten into ticks once,
     *  and an object it includes contributes its caption but no value path, so a
     *  shorthand selection never reaches below an object. */
    @Test
    void aShorthandSelectionKeysOnlyTheValuesItTicks() {
        Item child = new Item("child", List.of(), List.of(), List.of(), Map.of());
        Item item = new Item("one", List.of("t1"), List.of("en"), List.of(child), Map.of());

        List<objectview.field.FieldPath> paths =
                extractor.paths(item, ViewConfig.of(Item.class));

        assertTrue(paths.contains(objectview.field.FieldPath.of("tags")), paths::toString);
        assertFalse(paths.contains(objectview.field.FieldPath.of("children")),
                "an object without ticks below it is not a key value: " + paths);
        assertTrue(paths.stream().allMatch(path -> path.size() == 1),
                "shorthand never selects below an object: " + paths);
    }

    private static ViewConfig selected(
            Class<? extends objectview.Viewable> type, String... fields) {
        ViewConfig config = ViewConfig.of(type);
        config.setAllFields(false);
        for (String field : fields) {
            config.addField(field, ViewConfig.leaf());
        }
        return config;
    }

    @SuppressWarnings("unused")
    private static final class Item extends ViewableAdapter {
        private final String name;
        private final List<String> tags;
        private final List<String> languages;
        private final List<Item> children;
        private final Map<String, String> places;

        Item(String name, List<String> tags, List<String> languages,
             List<Item> children, Map<String, String> places) {
            this.name = name;
            this.tags = tags;
            this.languages = languages;
            this.children = children;
            this.places = places;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }
}
