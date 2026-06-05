package quiz.ui;

/**
 * Keeps only one manually-painted text component selected at a time.
 *
 * This avoids the confusing state where several QuizableTextRow /
 * QuizableTextBlock components appear selected, while copy uses only
 * the last one.
 */
final class QuizableTextSelectionManager {

    private static QuizableTextSelectable current;

    private QuizableTextSelectionManager() {
    }

    static void activate(QuizableTextSelectable selectable) {
        if (selectable == null) {
            clearCurrent();
            return;
        }

        if (current != null && current != selectable) {
            current.clearSelectionFromManager();
        }

        current = selectable;
    }

    static void clearCurrent() {
        if (current != null) {
            QuizableTextSelectable old = current;
            current = null;
            old.clearSelectionFromManager();
        }
    }
}
