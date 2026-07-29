package quiz.ui;

/**
 * Layout and interaction policy for a desktop choice grid.
 */
public record ChoiceBoardPolicy(
        int columns,
        QuizCardRole cardRole,
        ChoiceSelectionMode selectionMode,
        boolean framedIdle) {

    public ChoiceBoardPolicy {
        if (columns < 1) {
            throw new IllegalArgumentException("ChoiceBoard needs at least one column");
        }
        cardRole = cardRole == null ? QuizCardRole.OPTION : cardRole;
        selectionMode = selectionMode == null
                ? ChoiceSelectionMode.EXTERNAL : selectionMode;
    }

    public static ChoiceBoardPolicy answers(int columns) {
        return new ChoiceBoardPolicy(
                columns, QuizCardRole.OPTION,
                ChoiceSelectionMode.EXTERNAL, true);
    }
}
