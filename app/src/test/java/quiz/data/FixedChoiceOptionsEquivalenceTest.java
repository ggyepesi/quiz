package quiz.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-choice sampling keeps what the shuffle it replaced guaranteed.
 *
 * <p>Options used to come from copying and shuffling the whole answer pool; they are now
 * reservoir-sampled in one pass, and the correct answers — possibly a lazy Cartesian
 * view — are turned into a set once per round instead of scanned per candidate. This
 * checks the invariants on random pools, that the distribution of distractors and of
 * the chosen correct answer is the old one's (uniform), and that handing the correct
 * answers over as a list or as a set changes nothing but the cost.
 */
class FixedChoiceOptionsEquivalenceTest {

    /** The pre-reservoir option builder, kept as the reference distribution. */
    static List<String> shuffled(List<String> correct, Set<String> exclude, List<String> pool,
                                 int count, Random random) {
        LinkedHashSet<String> options = new LinkedHashSet<>();
        options.add(correct.get(random.nextInt(correct.size())));
        List<String> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, random);
        for (String value : copy) {
            if (options.size() >= count) break;
            if (exclude.contains(value)) continue;
            options.add(value);
        }
        List<String> list = new ArrayList<>(options);
        Collections.shuffle(list, random);
        return list;
    }

    @Test void everyRoundHasOneCorrectAnswerNoRepeatsAndTheExpectedSize() {
        Random random = new Random(20261005L);
        for (int trial = 0; trial < 5_000; trial++) {
            List<String> pool = values(random, "v", 1 + random.nextInt(15), 12);
            List<String> correct = new ArrayList<>(pool.subList(0, 1 + random.nextInt(
                    Math.min(3, pool.size()))));
            int count = 1 + random.nextInt(5);

            List<String> options = FixedChoiceOptions.choose(correct, correct, pool, count, random);

            Set<String> eligible = new LinkedHashSet<>(pool);
            eligible.removeAll(correct);
            String context = "trial " + trial + " correct " + correct + " pool " + pool;
            assertEquals(Math.min(count, 1 + eligible.size()), options.size(), context);
            assertEquals(options.size(), new HashSet<>(options).size(), "no repeats, " + context);
            assertEquals(1, options.stream().filter(correct::contains).count(),
                    "exactly one correct answer, " + context);
            assertTrue(pool.containsAll(options), context);
        }
    }

    /** Uniform over eligible distractors and over the correct answers, as before. */
    @Test void distractorsAndTheCorrectAnswerAreDistributedAsTheShuffleDistributedThem() {
        List<String> correct = List.of("c0", "c1", "c2");
        List<String> pool = new ArrayList<>(correct);
        for (int i = 0; i < 9; i++) pool.add("d" + i);
        int trials = 60_000;

        Map<String, Integer> reservoir = frequencies(trials, 1L, random ->
                FixedChoiceOptions.choose(correct, correct, pool, 4, random));
        Map<String, Integer> shuffle = frequencies(trials, 2L, random ->
                shuffled(correct, new HashSet<>(correct), pool, 4, random));

        // Each round shows 1 of 3 correct and 3 of 9 distractors.
        double correctShare = trials / 3.0;
        double distractorShare = trials * 3 / 9.0;
        for (String value : pool) {
            double expected = correct.contains(value) ? correctShare : distractorShare;
            assertWithin(expected, reservoir.getOrDefault(value, 0), value + " (reservoir)");
            assertWithin(expected, shuffle.getOrDefault(value, 0), value + " (shuffle)");
        }
    }

    /** A lazy view of the correct answers and a set of them give identical rounds for the
     *  same random source: the set only changes how membership is answered. */
    @Test void handingTheCorrectAnswersOverAsAListOrASetGivesTheSameRounds() {
        List<List<Object>> correct = new ViewableKeyExtractor.LazyCartesianKeys(List.of(
                List.of("q", "r"), List.of("a", "b", "c")));
        List<List<Object>> pool = new ArrayList<>(correct);
        for (int i = 0; i < 40; i++) pool.add(List.of("other", i));
        for (int seed = 0; seed < 200; seed++) {
            assertEquals(
                    FixedChoiceOptions.choose(correct, new HashSet<>(correct), pool, 4,
                            new Random(seed)),
                    FixedChoiceOptions.choose(correct, correct, pool, 4, new Random(seed)),
                    "seed " + seed);
        }
    }

    /** One answer among the not-exhausted occurrences, uniformly, as picking from a
     *  filtered copy did; none when every one is exhausted. */
    @Test void pickingOneIsUniformOverEligibleOccurrences() {
        List<String> answers = List.of("a", "b", "b", "x", "c");
        Map<String, Integer> picks = new LinkedHashMap<>();
        Random random = new Random(3L);
        int trials = 40_000;
        for (int i = 0; i < trials; i++) {
            picks.merge(FixedChoiceOptions.pickOne(answers, value -> !"x".equals(value),
                    random), 1, Integer::sum);
        }

        assertEquals(Set.of("a", "b", "c"), picks.keySet());
        assertWithin(trials / 4.0, picks.get("a"), "a");
        assertWithin(trials / 2.0, picks.get("b"), "b occurs twice");
        assertWithin(trials / 4.0, picks.get("c"), "c");
        assertNull(FixedChoiceOptions.pickOne(answers, value -> false, random));
    }

    private interface Round {
        List<String> draw(Random random);
    }

    private static Map<String, Integer> frequencies(int trials, long seed, Round round) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Random random = new Random(seed);
        for (int i = 0; i < trials; i++) {
            for (String value : round.draw(random)) out.merge(value, 1, Integer::sum);
        }
        return out;
    }

    /** Within five standard deviations of a binomial count: a real bias fails, chance
     *  does not. */
    private static void assertWithin(double expected, int actual, String what) {
        double tolerance = 5 * Math.sqrt(expected);
        assertTrue(Math.abs(actual - expected) <= tolerance,
                what + ": expected about " + Math.round(expected) + ", got " + actual);
    }

    private static List<String> values(Random random, String prefix, int size, int alphabet) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        while (out.size() < Math.min(size, alphabet)) out.add(prefix + random.nextInt(alphabet));
        return new ArrayList<>(out);
    }
}
