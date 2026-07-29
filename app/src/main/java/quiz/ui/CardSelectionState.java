package quiz.ui;

/**
 * Interaction state layered around a rendered quiz Card.
 */
public enum CardSelectionState {
    IDLE,
    SELECTED,
    CORRECT,
    WRONG,
    MATCHED,
    EXHAUSTED,
    DISABLED
}
