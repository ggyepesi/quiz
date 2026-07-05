package quiz.transform.ui;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

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