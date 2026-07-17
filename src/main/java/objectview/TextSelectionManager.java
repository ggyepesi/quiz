package objectview;

/**
 * Keeps only one manually-painted text component selected at a time.
 *
 * This avoids the confusing state where several TextRow /
 * TextBlock components appear selected, while copy uses only
 * the last one.
 */
final class TextSelectionManager {

    private static TextSelectable current;

    private TextSelectionManager() {
    }

    static void activate(TextSelectable selectable) {
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
            TextSelectable old = current;
            current = null;
            old.clearSelectionFromManager();
        }
    }
}
