package quiz;

import domain.DelegatingDomainModel;
import domain.DomainModel;
import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.field.FieldPath;
import objectview.viewconfig.DomainGroupRoot;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import quiz.data.ViewableKeyExtractor;
import quiz.transform.ui.ReflectionDomain;

import javax.swing.JButton;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The selected served class, not a Java constant in QuizFactory, owns the quiz's
 * instances, fields and saved grouping. */
class QuizFactoryConfiguredDomainTest {

    static final class Person extends ViewableAdapter {
        final String label;
        final String occupation;
        Person(String label, String occupation) {
            this.label = label;
            this.occupation = occupation;
        }
        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
    }

    static final class Office extends ViewableAdapter {
        final String label;
        final int holderCount;
        Office(String label, int holderCount) {
            this.label = label;
            this.holderCount = holderCount;
        }
        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
    }

    static final class RelatedPerson extends ViewableAdapter {
        final String label;
        final String biography;
        RelatedPerson spouse;
        RelatedPerson(String label, String biography) {
            this.label = label;
            this.biography = biography;
        }
        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
    }

    @Test void servedClassDrivesInstancesGroupAndDynamicFieldRows() {
        Person person = new Person("Charles", "king");
        Office office = new Office("Apostolic King of Hungary", 53);
        quiz.group.ViewableGroup offices = new quiz.group.ViewableGroup("Offices");
        offices.addMember(office, false);
        DomainModel domain = domain(List.of(person, office), offices);

        assertEquals(List.of(
                        new QuizFactory.ServedClass("Person", 1),
                        new QuizFactory.ServedClass("Office", 1)),
                QuizFactory.servedClasses(domain));

        QuizFactory.QuizSource people = QuizFactory.sourceFor(domain, "Person");
        assertEquals(List.of(person), people.instances());
        assertEquals(List.of("Charles"), people.viewables().keySet().stream().toList());
        assertFalse(people.configuredGrouping());
        assertEquals("All Person", people.root().getDisplayName());
        assertEquals("Categorize is unavailable: class \"Person\" has no saved group root.",
                QuizFactory.quizTypeUnavailableReason(
                        QuizAnswerType.CATEGORIZE, "Person", false));
        assertNull(QuizFactory.quizTypeUnavailableReason(
                QuizAnswerType.ABCD, "Person", false));

        QuizFactory.QuizSource officeSource = QuizFactory.sourceFor(domain, "Office");
        assertEquals(List.of(office), officeSource.instances());
        assertTrue(officeSource.configuredGrouping());
        assertSame(offices, officeSource.root());

        var editor = QuizFactory.fieldEditor(domain, "Office", false);
        assertEquals(List.of(FieldPath.of("@view:display"),
                        FieldPath.of("label"), FieldPath.of("holderCount")),
                editor.selectedFieldPaths());
        var queryConfig = editor.getConfig();
        assertFalse(queryConfig.isThumb());
        assertNull(queryConfig.getCls(),
                "saved-domain field configs do not depend on a generated Java class");
        assertEquals(List.of(List.of(
                        "Apostolic King of Hungary",
                        "Apostolic King of Hungary",
                        53)),
                new ViewableKeyExtractor().combinations(office, queryConfig));
        assertTrue(QuizFactory.fieldEditor(domain, "Office", true)
                .getConfig().isThumb());
    }

    @Test void aClassOutsideTheServedContractCannotBeOpenedAsAQuizSource() {
        DomainModel domain = domain(
                List.of(new Person("Charles", "king"), new Office("Office", 1)),
                null);

        assertThrows(IllegalArgumentException.class,
                () -> QuizFactory.sourceFor(domain, "Name"));
    }

    @Test void onlyQuizKindsWithIndependentSidesRequireDisjointFields() {
        assertTrue(QuizAnswerType.LIST.requiresDisjointQuestionAndAnswerFields());
        assertTrue(QuizAnswerType.ABCD.requiresDisjointQuestionAndAnswerFields());
        assertTrue(QuizAnswerType.PAIRING.requiresDisjointQuestionAndAnswerFields());
        assertFalse(QuizAnswerType.CATEGORIZE.requiresDisjointQuestionAndAnswerFields());
        assertFalse(QuizAnswerType.SIXDEGREES.requiresDisjointQuestionAndAnswerFields());

        List<FieldPath> name = List.of(FieldPath.of("name"));
        assertEquals(
                "Question and answer fields must be disjoint. Used on both sides: name.",
                QuizFactory.fieldSelectionProblem(QuizAnswerType.ABCD, name, name));
        assertNull(QuizFactory.fieldSelectionProblem(
                QuizAnswerType.CATEGORIZE, name, name));
        assertNull(QuizFactory.fieldSelectionProblem(
                QuizAnswerType.SIXDEGREES, name, name));
    }

    @Test void aSelectedReferenceStartsAtItsDisplayAndNotItsObjectGraph() {
        RelatedPerson spouse = new RelatedPerson("Bob", "A long nested value");
        RelatedPerson person = new RelatedPerson("Alice", "Another biography");
        person.spouse = spouse;
        ReflectionDomain domain = new ReflectionDomain(List.of(person, spouse));

        var editor = QuizFactory.fieldEditor(domain, "RelatedPerson", false);
        List<FieldPath> selected = new ViewableKeyExtractor()
                .paths(person, editor.getConfig());

        assertTrue(selected.contains(FieldPath.parse("spouse.@view:display")), selected::toString);
        assertFalse(selected.contains(FieldPath.parse("spouse.biography")), selected::toString);
    }

    /** An answer starts as the instance's display name alone; the question side
     *  starts with every field. */
    @Test void anAnswerStartsWithOnlyTheDisplayName() {
        RelatedPerson person = new RelatedPerson("Alice", "A biography");
        ReflectionDomain domain = new ReflectionDomain(List.of(person));

        List<FieldPath> answer = QuizFactory.fieldEditor(
                domain, "RelatedPerson", true).selectedFieldPaths();
        List<FieldPath> question = QuizFactory.fieldEditor(
                domain, "RelatedPerson", false).selectedFieldPaths();

        assertEquals(List.of(FieldPath.parse("@view:display")), answer);
        assertTrue(question.contains(FieldPath.parse("biography")), question::toString);
        assertTrue(question.contains(FieldPath.parse("@view:display")), question::toString);
    }

    /** Ticking a field on one side unticks it on the other, visibly, and only there. */
    @Test void aFieldTickedOnOneSideIsUntickedOnTheOther() {
        RelatedPerson person = new RelatedPerson("Alice", "A biography");
        ReflectionDomain domain = new ReflectionDomain(List.of(person));
        var question = QuizFactory.fieldEditor(domain, "RelatedPerson", false);
        var answer = QuizFactory.fieldEditor(domain, "RelatedPerson", true);
        int[] questionChanges = {0};
        question.setChangeListener(() -> questionChanges[0]++);

        List<FieldPath> unticked = QuizFactory.keepDisjoint(answer, question);

        assertEquals(List.of(FieldPath.parse("@view:display")), unticked);
        assertFalse(question.selectedFieldPaths().contains(FieldPath.parse("@view:display")));
        assertTrue(question.selectedFieldPaths().contains(FieldPath.parse("biography")),
                "a field ticked only on the question side stays");
        assertEquals(List.of(FieldPath.parse("@view:display")), answer.selectedFieldPaths(),
                "the side just ticked is not changed");
        assertTrue(questionChanges[0] > 0, "the untick is announced like a click");
        assertEquals(List.of(), QuizFactory.keepDisjoint(answer, question),
                "nothing left to resolve");
    }

    /** A ticked reference with nothing ticked under it means its display name again,
     *  so unticking its display name on the other side unticks the reference too. */
    @Test void aReferenceLeftWithNothingTickedIsUntickedToo() {
        RelatedPerson person = marriedPerson();
        ReflectionDomain domain = new ReflectionDomain(List.of(person, person.spouse));
        var question = QuizFactory.fieldEditor(domain, "RelatedPerson", false);
        var answer = QuizFactory.fieldEditor(domain, "RelatedPerson", false);

        QuizFactory.keepDisjoint(answer, question);

        List<FieldPath> left = new ViewableKeyExtractor().paths(person, question.getConfig());
        assertTrue(left.stream().noneMatch(path -> path.first().equals("spouse")),
                left::toString);
    }

    /** Only what conflicts is unticked: the spouse's display name, not the spouse's
     *  biography the question also asks about. */
    @Test void fieldsUnderAReferenceThatDoNotConflictStay() {
        RelatedPerson person = marriedPerson();
        ReflectionDomain domain = new ReflectionDomain(List.of(person, person.spouse));
        ViewConfig spouse = new ViewConfig();
        spouse.setAllFields(false);
        spouse.addField("@view:display", ViewConfig.leaf());
        spouse.addField("biography", ViewConfig.leaf());
        ViewConfig config = new ViewConfig();
        config.setAllFields(false);
        config.addField("label", ViewConfig.leaf());
        config.addField("spouse", spouse);
        var question = new objectview.viewconfig.ViewConfigEditor(config, true, person);
        question.setConfigRows(config, person, domain.fieldTypes("RelatedPerson"),
                domain.structuralFields("RelatedPerson"));
        var answer = QuizFactory.fieldEditor(domain, "RelatedPerson", false);

        QuizFactory.keepDisjoint(answer, question);

        List<FieldPath> left = new ViewableKeyExtractor().paths(person, question.getConfig());
        assertTrue(left.contains(FieldPath.parse("spouse.biography")), left::toString);
        assertFalse(left.contains(FieldPath.parse("spouse.@view:display")), left::toString);
    }

    private static RelatedPerson marriedPerson() {
        RelatedPerson spouse = new RelatedPerson("Bob", "A long nested value");
        RelatedPerson person = new RelatedPerson("Alice", "Another biography");
        person.spouse = spouse;
        return person;
    }

    @Test void creatingAQuizIsVisibleAndPreventsAnotherClick() {
        JButton button = new JButton("Create quiz");

        QuizFactory.setQuizCreationRunning(button, true);
        assertEquals("Creating quiz…", button.getText());
        assertFalse(button.isEnabled());

        QuizFactory.setQuizCreationRunning(button, false);
        assertEquals("Create quiz", button.getText());
        assertTrue(button.isEnabled());
    }

    private static DomainModel domain(
            List<? extends Viewable> values,
            quiz.group.ViewableGroup offices) {
        ReflectionDomain reflected = new ReflectionDomain(values);
        return new DelegatingDomainModel(reflected) {
            @Override public List<String> servedTypes() {
                return List.of("Person", "Office");
            }

            @Override public List<DomainGroupRoot> groupRootBindings() {
                return offices == null ? List.of()
                        : List.of(new DomainGroupRoot("Office", offices));
            }
        };
    }
}
