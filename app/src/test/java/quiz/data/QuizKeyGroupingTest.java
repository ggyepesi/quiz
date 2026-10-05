package quiz.data;

import objectview.ViewableAdapter;
import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.RandomAccess;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fields selected under one collection are combined per element of it (#345).
 *
 * <p>Each selected path used to collect its values across ALL elements and the paths
 * were then multiplied, as if the collection had been flattened: a ruler who was King
 * from 1301 and Duke from 1290 also produced "King, 1290" and "Duke, 1301" — two of
 * four questions that never existed. A key now takes one element's values together.
 * Paths that share no collection stay independent, so a person still contributes
 * (offices) × (spouses) keys.
 */
class QuizKeyGroupingTest {

    private final ViewableKeyExtractor extractor = new ViewableKeyExtractor();

    @Test void anOfficesPositionAndStartComeFromTheSameOffice() {
        Person person = new Person("Charles");
        person.offices.add(new Office("King", "1301"));
        person.offices.add(new Office("Duke", "1290"));

        assertEquals(List.of(List.of("King", "1301"), List.of("Duke", "1290")),
                keys(person, "offices.position", "offices.start"));
    }

    @Test void differentCollectionsStayIndependentFactors() {
        Person person = new Person("Charles");
        person.offices.add(new Office("King", "1301"));
        person.offices.add(new Office("Duke", "1290"));
        person.spouse.add(new Person("Maria"));
        person.spouse.add(new Person("Elisabeth"));
        person.spouse.add(new Person("Beatrice"));

        List<List<Object>> keys = keys(person, "offices.position", "offices.start", "spouse");

        assertEquals(2 * 3, keys.size(), "(offices) × (spouses)");
        assertEquals(Set.of(
                List.of("King", "1301", "Maria"), List.of("King", "1301", "Elisabeth"),
                List.of("King", "1301", "Beatrice"), List.of("Duke", "1290", "Maria"),
                List.of("Duke", "1290", "Elisabeth"), List.of("Duke", "1290", "Beatrice")),
                new HashSet<>(keys));
    }

    /** The other side of the distinction: within ONE element, a multi-valued field
     *  still multiplies with that element's other fields. */
    @Test void withinOneElementItsOwnValuesStillCombine() {
        Person person = new Person("Charles");
        Office king = new Office("King", "1301");
        king.titles.addAll(List.of("Rex", "Király"));
        person.offices.add(king);
        Office duke = new Office("Duke", "1290");
        duke.titles.add("Dux");
        person.offices.add(duke);

        assertEquals(List.of(List.of("King", "Rex"), List.of("King", "Király"),
                        List.of("Duke", "Dux")),
                keys(person, "offices.position", "offices.titles"));
    }

    @Test void anElementMissingOneSelectedFieldContributesNothingButOthersStay() {
        Person person = new Person("Charles");
        person.offices.add(new Office("King", "1301"));
        person.offices.add(new Office("Count", null));

        assertEquals(List.of(List.of("King", "1301")),
                keys(person, "offices.position", "offices.start"));
        assertEquals(List.of(), keys(personWithOnly(new Office("Count", null)),
                "offices.position", "offices.start"), "no element has both");
    }

    /** One path through a collection was already right; it is unchanged. */
    @Test void aSinglePathThroughACollectionIsTheFlattenedValues() {
        Person person = new Person("Charles");
        person.offices.add(new Office("King", "1301"));
        person.offices.add(new Office("Duke", "1290"));

        assertEquals(List.of(List.of("King"), List.of("Duke")),
                keys(person, "offices.position"));
        assertEquals(extractor.alternatives(person, FieldPath.parse("offices.position")),
                keys(person, "offices.position").stream().map(List::getFirst).toList());
    }

    /** A single referenced object has one element, so its fields multiply as before. */
    @Test void fieldsOfASingleReferenceStillMultiply() {
        Person person = new Person("Charles");
        person.name = new Name(List.of("Charles", "Robert"), "Anjou");

        assertEquals(List.of(List.of("Charles", "Anjou"), List.of("Robert", "Anjou")),
                keys(person, "name.given", "name.family"));
    }

    /** The key keeps the selected paths' order whatever order they are grouped in. */
    @Test void aKeyIsLaidOutInSelectedPathOrder() {
        Person person = new Person("Charles");
        person.offices.add(new Office("King", "1301"));
        person.spouse.add(new Person("Maria"));

        assertEquals(List.of(List.of("1301", "Maria", "King")),
                keys(person, "offices.start", "spouse", "offices.position"));
    }

    /** A path selected twice fills both of its positions, as one factor per path did. */
    @Test void aPathSelectedTwiceFillsBothPositions() {
        Person person = new Person("Charles");
        person.offices.add(new Office("King", "1301"));
        person.offices.add(new Office("Duke", "1290"));

        assertEquals(List.of(List.of("King", "1301", "King"), List.of("Duke", "1290", "Duke")),
                keys(person, "offices.position", "offices.start", "offices.position"));
    }

    @Test void aGroupTooLargeToIndexIsNamedByItsPaths() {
        Person person = new Person("Charles");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) many.add("v" + i);
        Office office = new Office("King", "1301");
        office.titles.addAll(many);
        office.seats.addAll(many);
        person.offices.add(office);

        ViewableKeyExtractor.TooManyCombinations refused = assertThrows(
                ViewableKeyExtractor.TooManyCombinations.class,
                () -> keys(person, "offices.titles", "offices.seats"));
        assertTrue(refused.getMessage().contains("offices.titles (50000 values)")
                && refused.getMessage().contains("offices.seats (50000 values)"),
                refused.getMessage());
    }

    /** Correlation must not undo the lazy-product rule: sizing a grouped element's
     *  product must not read and merge every combination. */
    @Test void aGroupedElementProductIsNotMaterializedWhenItIsSized() {
        CountingList first = new CountingList(1_000);
        CountingList second = new CountingList(1_000);
        var product = new ViewableKeyExtractor.LazyCartesianKeys(
                List.of(first, second));
        var grouped = new ViewableKeyExtractor.MergedPartials(product);

        assertEquals(1_000_000, grouped.size());
        assertEquals(0, first.reads + second.reads,
                "no combination is read before the quiz requests it");
    }

    @Test void collectionElementProductsAreConcatenatedWithoutReadingThem() {
        CountingList first = new CountingList(2);
        CountingList second = new CountingList(3);
        var grouped = new ViewableKeyExtractor.ConcatenatedPartials(
                List.of(first, second), 5);

        assertEquals(5, grouped.size());
        assertEquals(0, first.reads + second.reads);
        assertEquals("v2", grouped.getLast());
        assertEquals(0, first.reads);
        assertEquals(1, second.reads,
                "only the collection element containing the requested tuple is read");
    }

    private List<List<Object>> keys(Person person, String... dotted) {
        List<FieldPath> paths = new ArrayList<>();
        for (String path : dotted) paths.add(FieldPath.parse(path));
        return new ArrayList<>(extractor.combinations(person, paths));
    }

    private static Person personWithOnly(Office office) {
        Person person = new Person("Solo");
        person.offices.add(office);
        return person;
    }

    private static final class CountingList extends AbstractList<Object>
            implements RandomAccess {
        private final int size;
        private int reads;

        private CountingList(int size) {
            this.size = size;
        }

        @Override public Object get(int index) {
            java.util.Objects.checkIndex(index, size);
            reads++;
            return "v" + index;
        }

        @Override public int size() {
            return size;
        }
    }

    @SuppressWarnings("unused")
    static final class Person extends ViewableAdapter {
        private final String label;
        final List<Office> offices = new ArrayList<>();
        final List<Person> spouse = new ArrayList<>();
        Name name;

        Person(String label) { this.label = label; }

        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
        @Override public String getName() { return label; }
    }

    @SuppressWarnings("unused")
    static final class Office extends ViewableAdapter {
        private final String position;
        private final String start;
        final List<String> titles = new ArrayList<>();
        final List<String> seats = new ArrayList<>();

        Office(String position, String start) {
            this.position = position;
            this.start = start;
        }

        @Override public String getIdentifier() { return position; }
        @Override public String getDisplayName() { return position; }
    }

    @SuppressWarnings("unused")
    static final class Name extends ViewableAdapter {
        private final List<String> given;
        private final String family;

        Name(List<String> given, String family) {
            this.given = given;
            this.family = family;
        }

        @Override public String getIdentifier() { return family; }
        @Override public String getDisplayName() { return family; }
    }
}
