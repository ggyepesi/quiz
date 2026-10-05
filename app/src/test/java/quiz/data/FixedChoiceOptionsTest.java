package quiz.data;

import org.junit.jupiter.api.Test;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Desktop and web fixed-choice quizzes must not grow separate option rules. */
class FixedChoiceOptionsTest {

    @Test
    void returnsOneCorrectAndEnoughDistinctDistractorsForTheRequestedSize() {
        List<String> choices = FixedChoiceOptions.choose(
                List.of("correct"), Set.of("correct"),
                List.of("correct", "two", "three", "four", "five"),
                4, new Random(1));

        assertEquals(4, choices.size());
        assertEquals(4, choices.stream().distinct().count());
        assertTrue(choices.contains("correct"));
    }

    @Test
    void otherCorrectValuesAreNotOfferedAsWrongDistractors() {
        List<String> choices = FixedChoiceOptions.choose(
                List.of("correct-a"), Set.of("correct-a", "correct-b"),
                List.of("correct-a", "correct-b", "two", "three", "four"),
                4, new Random(1));

        assertTrue(choices.contains("correct-a"));
        assertFalse(choices.contains("correct-b"));
    }

    @Test
    void aFixedSizeRoundDoesNotCopyItsWholeCandidatePool() {
        Collection<Integer> largePool = new AbstractCollection<>() {
            @Override public Iterator<Integer> iterator() {
                return java.util.stream.IntStream.range(0, 100_000)
                        .boxed().iterator();
            }

            @Override public int size() {
                return 100_000;
            }

            @Override public Object[] toArray() {
                throw new AssertionError("the candidate pool must not be copied");
            }

            @Override public <T> T[] toArray(T[] target) {
                throw new AssertionError("the candidate pool must not be copied");
            }
        };

        List<Integer> choices = FixedChoiceOptions.choose(
                List.of(0), Set.of(0), largePool, 4, new Random(1));

        assertEquals(4, choices.size());
        assertTrue(choices.contains(0));
    }
}
