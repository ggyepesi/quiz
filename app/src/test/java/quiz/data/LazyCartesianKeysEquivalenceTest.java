package quiz.data;

import objectview.ViewableAdapter;
import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lazy Cartesian key view is the eager product it replaced, element for element.
 *
 * <p>Quiz keys used to be built by a recursive builder that materialized every
 * combination; a multi-valued instance then held its whole product in memory. The lazy
 * view computes a combination on demand. Nothing about a quiz may change because of
 * that, so this compares the two on many seeded random shapes — empty, single and
 * duplicated alternatives, one to five fields — across the whole List contract the
 * quiz code and its collections use, and end to end through the extractor.
 */
class LazyCartesianKeysEquivalenceTest {

    private static final int SHAPES = 2_000;

    /** The pre-lazy builder, kept verbatim as the reference. */
    static List<List<Object>> eager(List<List<Object>> alternatives) {
        List<List<Object>> out = new ArrayList<>();
        build(alternatives, 0, new ArrayList<>(), out);
        return List.copyOf(out);
    }

    private static void build(List<List<Object>> lists, int index, List<Object> current,
                              List<List<Object>> out) {
        if (index == lists.size()) {
            out.add(List.copyOf(current));
            return;
        }
        for (Object value : lists.get(index)) {
            current.add(value);
            build(lists, index + 1, current, out);
            current.removeLast();
        }
    }

    @Test void theLazyViewIsTheEagerProductOnRandomShapes() {
        Random random = new Random(20261005L);
        for (int shape = 0; shape < SHAPES; shape++) {
            List<List<Object>> alternatives = randomAlternatives(random, 1 + random.nextInt(5));
            List<List<Object>> expected = eager(alternatives);
            List<List<Object>> lazy = new ViewableKeyExtractor.LazyCartesianKeys(alternatives);
            String context = "shape " + shape + " " + alternatives;

            assertEquals(expected.size(), lazy.size(), context);
            assertEquals(expected, lazy, context);
            assertEquals(lazy, expected, context);
            assertEquals(expected.hashCode(), lazy.hashCode(), context);
            assertEquals(expected, new ArrayList<>(lazy), "iteration order, " + context);
            assertEquals(Arrays.asList(expected.toArray()), Arrays.asList(lazy.toArray()), context);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), lazy.get(i), "index " + i + ", " + context);
                assertEquals(expected.indexOf(expected.get(i)),
                        lazy.indexOf(expected.get(i)), context);
                assertEquals(expected.lastIndexOf(expected.get(i)),
                        lazy.lastIndexOf(expected.get(i)), context);
                assertTrue(lazy.contains(expected.get(i)), context);
            }
            assertFalse(lazy.contains(List.of("absent")), context);
            if (expected.size() >= 2) {
                assertEquals(expected.subList(1, expected.size()),
                        lazy.subList(1, lazy.size()), context);
            }
            int size = lazy.size();
            assertThrows(IndexOutOfBoundsException.class, () -> lazy.get(size), context);
            assertThrows(IndexOutOfBoundsException.class, () -> lazy.get(-1), context);
            assertThrows(UnsupportedOperationException.class,
                    () -> lazy.add(List.of("x")), "the keys are immutable, as before");
        }
    }

    /** A combination is an immutable value like the eager one: usable as a map key. */
    @Test void aCombinationIsAnImmutableValueKey() {
        List<List<Object>> lazy = new ViewableKeyExtractor.LazyCartesianKeys(List.of(
                List.of("q1", "q2"), List.of("lang1", "lang2")));
        java.util.Map<List<Object>, String> byKey = new java.util.HashMap<>();
        byKey.put(lazy.get(1), "found");

        assertEquals("found", byKey.get(List.of("q1", "lang2")));
        assertThrows(UnsupportedOperationException.class, () -> lazy.get(0).set(0, "x"));
    }

    @Test void anEmptyAlternativeMakesNoCombinationsInBoth() {
        List<List<Object>> alternatives = List.of(List.of("a", "b"), List.of(), List.of("c"));

        assertEquals(eager(alternatives),
                new ViewableKeyExtractor.LazyCartesianKeys(alternatives));
        assertEquals(0, new ViewableKeyExtractor.LazyCartesianKeys(alternatives).size());
    }

    /** The eager builder would exhaust memory long before; the lazy view says why. */
    @Test void aProductBeyondTheListSizeLimitIsRefusedNotWrapped() {
        List<Object> thousand = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) thousand.add("v" + i);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new ViewableKeyExtractor.LazyCartesianKeys(
                        List.of(thousand, thousand, thousand, thousand)));

        assertTrue(refused.getMessage().contains("combinations"), refused.getMessage());
    }

    /** End to end: the extractor's lazy keys equal the eager product of the same
     *  per-field alternatives it extracts, empty and blank values dropped. */
    @Test void theExtractorProducesTheEagerProductOfItsAlternatives() {
        ViewableKeyExtractor extractor = new ViewableKeyExtractor();
        Random random = new Random(7L);
        List<FieldPath> paths = List.of(FieldPath.parse("first"), FieldPath.parse("second"),
                FieldPath.parse("third"));
        for (int shape = 0; shape < 500; shape++) {
            Item item = new Item("i" + shape, randomValues(random), randomValues(random),
                    randomValues(random));
            List<List<Object>> alternatives = new ArrayList<>();
            for (FieldPath path : paths) {
                List<Object> values = new ArrayList<>(extractor.alternatives(item, path));
                alternatives.add(values);
            }
            boolean anyEmpty = alternatives.stream().anyMatch(List::isEmpty);
            List<List<Object>> expected = anyEmpty ? List.of() : eager(alternatives);

            assertEquals(expected, extractor.combinations(item, paths),
                    "item " + item.getIdentifier() + " " + alternatives);
        }
    }

    private static List<List<Object>> randomAlternatives(Random random, int fields) {
        List<List<Object>> out = new ArrayList<>();
        for (int field = 0; field < fields; field++) {
            int size = random.nextInt(5);
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                // A small alphabet so duplicates within a field are common.
                values.add(random.nextBoolean() ? "v" + random.nextInt(3) : random.nextInt(3));
            }
            out.add(List.copyOf(values));
        }
        return out;
    }

    private static List<String> randomValues(Random random) {
        int size = random.nextInt(4);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            int pick = random.nextInt(5);
            values.add(pick == 4 ? " " : "v" + pick);
        }
        return values;
    }

    @SuppressWarnings("unused")
    private static final class Item extends ViewableAdapter {
        private final String name;
        private final List<String> first;
        private final List<String> second;
        private final List<String> third;

        Item(String name, List<String> first, List<String> second, List<String> third) {
            this.name = name;
            this.first = first;
            this.second = second;
            this.third = third;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }
}
