package quiz;

import objectview.ViewableAdapter;
import objectview.field.FieldPath;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import quiz.group.ViewableGroup;
import quiz.transform.FacetGroup;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizCategorizeFieldExclusionTest {

    private static final class Team extends ViewableAdapter {
        String league = "NHL";
        String city = "Budapest";
        @Override public String getIdentifier() { return city; }
        @Override public String getDisplayName() { return city; }
    }

    @Test void categorizeOffersEveryQuestionFieldExceptItsFacetField() {
        FacetGroup leagues = new FacetGroup("Leagues", "Team", "league");

        assertEquals(Set.of(FieldPath.of("league")),
                QuizFactory.categorizeExcludedFields(
                        QuizAnswerType.CATEGORIZE, leagues));
        assertTrue(QuizFactory.categorizeExcludedFields(
                QuizAnswerType.ABCD, leagues).isEmpty());
        assertTrue(QuizFactory.categorizeExcludedFields(
                QuizAnswerType.CATEGORIZE, new ViewableGroup("Manual")).isEmpty());
    }

    @Test void directCategorizeConstructionCannotPutTheAnswerOnTheQuestionCard() {
        ViewConfig all = ViewConfig.all(Team.class);
        FacetGroup leagues = new FacetGroup("Leagues", "Team", "league");

        ViewConfig questions = QuizCategorize.withoutCategoryField(all, leagues);

        assertFalse(questions.isAllFields());
        assertFalse(questions.hasField("league"));
        assertTrue(questions.hasField("city"));
    }
}
