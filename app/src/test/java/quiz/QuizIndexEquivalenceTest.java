package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import quiz.data.ViewableKeyExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The shared quiz index built over lazy keys is the index the eager pairs built.
 *
 * <p>The index used to append every question-answer pair; it now shares one lazy answer
 * list per instance and concatenates them only where several instances give the same
 * question value. Every quiz type reads this index — List, ABCD and Pairing choose and
 * judge from it, and exhaustion counts from it — so it must be the same index: the same
 * question keys in the same order, the same correct answers in the same order for each,
 * the same first instance per key, and the same exhaustion allowances. This compares
 * the two on seeded random domains with collisions between instances and repeated
 * values within one, with and without a group selection.
 */
class QuizIndexEquivalenceTest {

    /** The pre-lazy indexViewables, kept verbatim as the reference. */
    private record EagerIndex(Map<List<Object>, List<List<Object>>> answersToQuery,
                              Map<List<Object>, Viewable> queryViewables,
                              Map<List<Object>, Viewable> answerViewables,
                              Map<List<Object>, Integer> correctAnswerUseCount) {

        static EagerIndex of(Map<String, ? extends Viewable> viewables, ViewConfig queryConfig,
                             ViewConfig answerConfig, objectview.group.ViewableGroup<?> group) {
            ViewableKeyExtractor extractor = new ViewableKeyExtractor();
            EagerIndex index = new EagerIndex(new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new HashMap<>());
            for (Viewable viewable : viewables.values()) {
                if (viewable == null) continue;
                if (group != null && !group.contains(viewable.getIdentifier())) continue;
                List<List<Object>> queryKeys = eagerKeys(extractor, viewable, queryConfig);
                List<List<Object>> answerKeys = eagerKeys(extractor, viewable, answerConfig);
                if (queryKeys.isEmpty() || answerKeys.isEmpty()) continue;
                for (List<Object> qk : queryKeys) {
                    for (List<Object> ak : answerKeys) {
                        index.answersToQuery.computeIfAbsent(qk, k -> new ArrayList<>()).add(ak);
                        index.queryViewables.putIfAbsent(qk, viewable);
                        index.answerViewables.putIfAbsent(ak, viewable);
                        index.correctAnswerUseCount.merge(ak, 1, Integer::sum);
                    }
                }
            }
            return index;
        }

        /** Keys as the pre-lazy extractor built them: every combination materialized by
         *  the recursive builder, over the same per-field alternatives. */
        static List<List<Object>> eagerKeys(ViewableKeyExtractor extractor, Viewable viewable,
                                            ViewConfig config) {
            List<List<Object>> alternatives = new ArrayList<>();
            for (objectview.field.FieldPath path : extractor.paths(viewable, config)) {
                List<Object> values = new ArrayList<>(extractor.alternatives(viewable, path));
                if (values.isEmpty()) return List.of();
                alternatives.add(values);
            }
            if (alternatives.isEmpty()) return List.of();
            List<List<Object>> out = new ArrayList<>();
            build(alternatives, 0, new ArrayList<>(), out);
            return out;
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
    }

    @Test void theLazyIndexIsTheEagerIndexOnRandomDomains() {
        Random random = new Random(20261005L);
        for (int domain = 0; domain < 300; domain++) {
            Map<String, Item> items = randomDomain(random, domain);
            boolean twoQueryFields = random.nextBoolean();
            compare("domain " + domain, items, queryConfig(twoQueryFields), answerConfig(), null);
        }
    }

    /** A group selects which instances are indexed; both indexes skip the rest. */
    @Test void aGroupSelectionIndexesTheSameInstancesInBoth() {
        Random random = new Random(42L);
        for (int domain = 0; domain < 100; domain++) {
            Map<String, Item> items = randomDomain(random, domain);
            quiz.group.ViewableGroup selected = new quiz.group.ViewableGroup("selected");
            items.values().stream().filter(item -> random.nextBoolean())
                    .forEach(item -> selected.addMember(item, false));
            compare("grouped domain " + domain, items, queryConfig(true), answerConfig(),
                    selected);
        }
    }

    /** The case the change exists for: one instance with many values on both sides,
     *  which the eager index stored as every pair. */
    @Test void aLargeMultiValuedInstanceIndexesTheSameAnswers() {
        Map<String, Item> items = new LinkedHashMap<>();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) many.add("v" + i);
        items.put("large", new Item("large", many, List.of("l1", "l2"), many));
        items.put("other", new Item("other", List.of("v7"), List.of("l1"), List.of("x")));

        compare("large", items, queryConfig(true), answerConfig(), null);
    }

    /** The History Person report: one instance whose selected fields multiply past the
     *  int range threw from the quiz constructor on the event thread. The quiz now
     *  indexes nothing and prepareQuiz names the instance and the fields to narrow. */
    @Test void aSelectionOneInstanceCannotIndexIsRefusedByNameNotThrown() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) many.add("v" + i);
        Map<String, Item> items = new LinkedHashMap<>();
        items.put("ordinary", new Item("ordinary", List.of("q"), List.of("l"), List.of("a")));
        items.put("huge", new Item("huge", many, many, List.of("b")));

        IndexedQuiz quiz = new IndexedQuiz(queryConfig(true), answerConfig(), null, items);
        try {
            String problem = quiz.prepareQuiz();
            org.junit.jupiter.api.Assertions.assertNotNull(problem);
            org.junit.jupiter.api.Assertions.assertTrue(problem.contains("huge")
                    && problem.contains("queries (50000 values)")
                    && problem.contains("languages (50000 values)"), problem);
            org.junit.jupiter.api.Assertions.assertTrue(quiz.answers().isEmpty(),
                    "nothing is half-indexed");
        } finally {
            quiz.dispose();
        }
    }

    private static void compare(String context, Map<String, Item> items,
                                ViewConfig queryConfig, ViewConfig answerConfig,
                                objectview.group.ViewableGroup<?> group) {
        EagerIndex expected = EagerIndex.of(items, queryConfig, answerConfig, group);
        IndexedQuiz lazy = new IndexedQuiz(queryConfig, answerConfig, group, items);
        try {
            assertEquals(new ArrayList<>(expected.answersToQuery().keySet()),
                    new ArrayList<>(lazy.answers().keySet()), "question order, " + context);
            for (Map.Entry<List<Object>, List<List<Object>>> question
                    : expected.answersToQuery().entrySet()) {
                List<List<Object>> actual = lazy.answers().get(question.getKey());
                assertEquals(question.getValue(), new ArrayList<>(actual),
                        "answers of " + question.getKey() + ", " + context);
                assertEquals(question.getValue().size(), actual.size(), context);
                for (int i = 0; i < actual.size(); i++) {
                    assertEquals(question.getValue().get(i), actual.get(i),
                            "random access " + i + " of " + question.getKey() + ", " + context);
                }
            }
            assertEquals(new ArrayList<>(expected.queryViewables().keySet()),
                    new ArrayList<>(lazy.questions().keySet()), context);
            expected.queryViewables().forEach((key, viewable) ->
                    assertSame(viewable, lazy.questions().get(key), context));
            assertEquals(new ArrayList<>(expected.answerViewables().keySet()),
                    new ArrayList<>(lazy.answerInstances().keySet()), context);
            expected.answerViewables().forEach((key, viewable) ->
                    assertSame(viewable, lazy.answerInstances().get(key), context));
            assertEquals(expected.correctAnswerUseCount(), lazy.useCounts(),
                    "exhaustion allowances, " + context);
        } finally {
            lazy.dispose();
        }
    }

    private static Map<String, Item> randomDomain(Random random, int domain) {
        Map<String, Item> items = new LinkedHashMap<>();
        int size = 1 + random.nextInt(8);
        for (int i = 0; i < size; i++) {
            String id = "d" + domain + "i" + i;
            items.put(id, new Item(id, values(random, "q", 4), values(random, "l", 2),
                    values(random, "a", 5)));
        }
        return items;
    }

    /** A few values from a small alphabet: collisions across instances and repeats within
     *  one are common, and some instances have none. */
    private static List<String> values(Random random, String prefix, int alphabet) {
        int size = random.nextInt(4);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < size; i++) out.add(prefix + random.nextInt(alphabet));
        return out;
    }

    private static ViewConfig queryConfig(boolean twoFields) {
        ViewConfig config = ViewConfig.of(Item.class);
        config.setAllFields(false);
        config.addField("queries", ViewConfig.leaf());
        if (twoFields) config.addField("languages", ViewConfig.leaf());
        return config;
    }

    private static ViewConfig answerConfig() {
        ViewConfig config = ViewConfig.of(Item.class);
        config.setAllFields(false);
        config.addField("answers", ViewConfig.leaf());
        return config;
    }

    private static final class IndexedQuiz extends Quiz {
        IndexedQuiz(ViewConfig queryConfig, ViewConfig answerConfig,
                    objectview.group.ViewableGroup<?> group, Map<String, ? extends Viewable> items) {
            super(queryConfig, answerConfig, group, items);
        }

        @Override public void run() { }

        Map<List<Object>, List<List<Object>>> answers() { return answersToQuery; }
        Map<List<Object>, Viewable> questions() { return queryViewables; }
        Map<List<Object>, Viewable> answerInstances() { return answerViewables; }
        Map<List<Object>, Integer> useCounts() { return correctAnswerUseCount; }

        void dispose() { if (frame != null) frame.dispose(); }
    }

    @SuppressWarnings("unused")
    private static final class Item extends ViewableAdapter {
        private final String name;
        private final List<String> queries;
        private final List<String> languages;
        private final List<String> answers;

        Item(String name, List<String> queries, List<String> languages, List<String> answers) {
            this.name = name;
            this.queries = queries;
            this.languages = languages;
            this.answers = answers;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
    }
}
