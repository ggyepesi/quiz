package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An answer card is judged and exhausted by the answer key it stands for.
 *
 * <p>Once each key rendered its own assembled object (#347), one instance could supply
 * several different-looking answer cards — X was King and Duke — while correctness and
 * "used" still compared the owning instance. For a question whose answer is King, X's
 * Duke card was then accepted too, and choosing either card used up all of X's answers.
 * Before that, the name was compared, so a distractor whose instance shared a name with
 * the correct one was accepted (#342). Neither the name nor the instance decides; the key
 * does.
 */
class QuizAnswerKeyScoringTest {

    @Test void aDistractorFromTheSameInstanceAsTheAnswerIsNotCorrect() {
        Person x = new Person("X", "1270", "King", "Duke");
        Person z = new Person("Z", "1300", "King");
        QuizListABCD quiz = abcd(x, z);
        try {
            Viewable king = quiz.answerCardItem(List.of("King")).source();
            Viewable duke = quiz.answerCardItem(List.of("Duke")).source();
            assertNotSame(king, duke, "each answer key is its own card");

            assertTrue(quiz.isCorrectChoice(List.of("1300"), king));
            assertFalse(quiz.isCorrectChoice(List.of("1300"), duke),
                    "X also supplies Duke, but Duke is not Z's answer");
            assertTrue(quiz.isCorrectChoice(List.of("1270"), duke));
        } finally {
            quiz.frame.dispose();
        }
    }

    @Test void choosingAnAnswerUsesOnlyThatAnswer() {
        Person x = new Person("X", "1270", "King", "Duke");
        QuizListABCD quiz = abcd(x, new Person("Y", "1280", "Count"));
        try {
            quiz.markAnswerAsUsed(quiz.answerKeyOf(quiz.answerCardItem(List.of("King")).source()));

            assertTrue(quiz.isExhausted(List.of("King")));
            assertFalse(quiz.isExhausted(List.of("Duke")),
                    "the same instance's other answer was not chosen");
        } finally {
            quiz.frame.dispose();
        }
    }

    @Test void aDistractorSharingANameWithTheCorrectInstanceIsNotCorrect() {
        Person correct = new Person("Same", "1300", "King");
        Person namesake = new Person("Same", "1301", "Duke");
        Map<String, Person> items = new LinkedHashMap<>();
        items.put("one", correct);
        items.put("two", namesake);
        QuizListABCD quiz = new QuizListABCD(born(), positions(),
                QuizAnswerType.ABCD, null, items);
        try {
            assertTrue(quiz.isCorrectChoice(List.of("1300"),
                    quiz.answerCardItem(List.of("King")).source()));
            assertFalse(quiz.isCorrectChoice(List.of("1300"),
                    quiz.answerCardItem(List.of("Duke")).source()),
                    "the same name is not the same answer");
        } finally {
            quiz.frame.dispose();
        }
    }

    @Test void somethingThatIsNotAnAnswerCardIsNeverCorrect() {
        Person x = new Person("X", "1270", "King");
        QuizListABCD quiz = abcd(x, new Person("Y", "1280", "Count"));
        try {
            assertFalse(quiz.isCorrectChoice(List.of("1270"), x),
                    "the owning instance is provenance, not a chosen answer");
            assertEquals(null, quiz.answerKeyOf(x));
        } finally {
            quiz.frame.dispose();
        }
    }

    private static QuizListABCD abcd(Person... people) {
        Map<String, Person> items = new LinkedHashMap<>();
        for (Person person : people) items.put(person.getIdentifier(), person);
        return new QuizListABCD(born(), positions(), QuizAnswerType.ABCD, null, items);
    }

    private static ViewConfig born() {
        ViewConfig config = ViewConfig.of(Person.class);
        config.setAllFields(false);
        config.addField("born", ViewConfig.leaf());
        return config;
    }

    private static ViewConfig positions() {
        ViewConfig office = ViewConfig.of(Office.class);
        office.setAllFields(false);
        office.addField("position", ViewConfig.leaf());
        ViewConfig config = ViewConfig.of(Person.class);
        config.setAllFields(false);
        config.addField("offices", office);
        return config;
    }

    @SuppressWarnings("unused")
    static final class Person extends ViewableAdapter {
        private final String label;
        private final String born;
        final List<Office> offices = new ArrayList<>();

        Person(String label, String born, String... positions) {
            this.label = label;
            this.born = born;
            for (String position : positions) offices.add(new Office(position));
        }

        @Override public String getIdentifier() { return label + "@" + born; }
        @Override public String getDisplayName() { return label; }
        @Override public String getName() { return label; }
    }

    @SuppressWarnings("unused")
    static final class Office extends ViewableAdapter {
        private final String position;

        Office(String position) { this.position = position; }

        @Override public String getIdentifier() { return position; }
        @Override public String getDisplayName() { return position; }
    }
}
