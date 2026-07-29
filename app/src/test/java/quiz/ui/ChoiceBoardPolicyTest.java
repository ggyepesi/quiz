package quiz.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChoiceBoardPolicyTest {
    @Test
    void answerBoardDefaultsToExternalTwoColumnOptions() {
        ChoiceBoardPolicy policy = ChoiceBoardPolicy.answers(2);

        assertEquals(2, policy.columns());
        assertEquals(QuizCardRole.OPTION, policy.cardRole());
        assertEquals(ChoiceSelectionMode.EXTERNAL, policy.selectionMode());
        assertTrue(policy.framedIdle());
    }

    @Test
    void nullRoleAndSelectionModeHaveSafeDefaults() {
        ChoiceBoardPolicy policy =
                new ChoiceBoardPolicy(3, null, null, false);

        assertEquals(QuizCardRole.OPTION, policy.cardRole());
        assertEquals(ChoiceSelectionMode.EXTERNAL, policy.selectionMode());
    }

    @Test
    void zeroColumnsAreRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                ChoiceBoardPolicy.answers(0));
    }
}
