package workbench;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * A document listener that says what changed once, not three times.
 *
 * <p>Shared, because both workbenches ask a text field to tell them it changed and one
 * of them had written the three-method boilerplate again privately. It lives here for
 * the same reason the source picker does: sharing means depending on {@code workbench},
 * which depends on neither app.
 */
public interface SimpleDocumentListener extends DocumentListener {

    void update();

    static SimpleDocumentListener of(Runnable r) {
        return () -> r.run();
    }

    @Override
    default void insertUpdate(DocumentEvent e) {
        update();
    }

    @Override
    default void removeUpdate(DocumentEvent e) {
        update();
    }

    @Override
    default void changedUpdate(DocumentEvent e) {
        update();
    }
}