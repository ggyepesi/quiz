package quiz.pairing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static quiz.pairing.PairingModel.ChoiceState.IDLE;
import static quiz.pairing.PairingModel.ChoiceState.MATCHED;
import static quiz.pairing.PairingModel.ChoiceState.SELECTED;

class PairingModelTest {

    @Test
    void selectsEitherSideAndCreatesOneToOneMatches() {
        PairingModel<String, String> model = new PairingModel<>(2);

        model.chooseLeft("L1");
        assertEquals(SELECTED, model.leftState("L1"));
        model.chooseRight("R1");

        assertEquals(MATCHED, model.leftState("L1"));
        assertEquals(MATCHED, model.rightState("R1"));
        assertEquals("R1", model.leftMatch("L1").orElseThrow().right());
        assertEquals(1, model.matchedPairs());
        assertFalse(model.isSolved());

        model.chooseRight("R2");
        model.chooseLeft("L2");

        assertEquals(2, model.matchedPairs());
        assertTrue(model.isSolved());
        assertEquals(List.of(1, 2),
                model.matches().stream()
                        .map(PairingModel.Match::number).toList());
    }

    @Test
    void clickingTheSelectedChoiceDeselectsIt() {
        PairingModel<String, String> model = new PairingModel<>(2);

        model.chooseLeft("L1");
        model.chooseLeft("L1");

        assertEquals(IDLE, model.leftState("L1"));
        assertTrue(model.selectedLeft().isEmpty());
    }

    @Test
    void selectingOnTheSameSideSwitchesTheActiveChoice() {
        PairingModel<String, String> model = new PairingModel<>(2);

        model.chooseRight("R1");
        model.chooseRight("R2");

        assertEquals(IDLE, model.rightState("R1"));
        assertEquals(SELECTED, model.rightState("R2"));
    }

    @Test
    void pairingWithAnAlreadyMatchedChoiceReassignsIt() {
        PairingModel<String, String> model = new PairingModel<>(2);
        model.chooseLeft("L1");
        model.chooseRight("R1");

        model.chooseLeft("L2");
        model.chooseRight("R1");

        assertEquals(IDLE, model.leftState("L1"));
        assertEquals(MATCHED, model.leftState("L2"));
        assertEquals("L2", model.rightMatch("R1").orElseThrow().left());
        assertEquals(1, model.matchedPairs());
        assertEquals(2, model.leftMatch("L2").orElseThrow().number());
    }

    @Test
    void selectingAMatchedChoiceUnpairsItBeforeSelection() {
        PairingModel<String, String> model = new PairingModel<>(2);
        model.chooseLeft("L1");
        model.chooseRight("R1");

        model.chooseLeft("L1");

        assertEquals(SELECTED, model.leftState("L1"));
        assertEquals(IDLE, model.rightState("R1"));
        assertEquals(0, model.matchedPairs());
    }

    @Test
    void solvedModelIgnoresFurtherChoices() {
        PairingModel<String, String> model = new PairingModel<>(1);
        model.chooseLeft("L1");
        model.chooseRight("R1");

        model.chooseLeft("L1");

        assertTrue(model.isSolved());
        assertEquals(MATCHED, model.leftState("L1"));
        assertEquals(MATCHED, model.rightState("R1"));
    }
}
