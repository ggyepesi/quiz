package objectview;

/**
 * Keeps only one manually-painted text component selected at a time.
 *
 * This avoids the confusing state where several ViewableTextRow /
 * ViewableTextBlock components appear selected, while copy uses only
 * the last one.
 */
final class ViewableTextSelectionManager {

    private static ViewableTextSelectable current;

    private ViewableTextSelectionManager() {
    }

    static void activate(ViewableTextSelectable selectable) {
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
            ViewableTextSelectable old = current;
            current = null;
            old.clearSelectionFromManager();
        }
    }
}
