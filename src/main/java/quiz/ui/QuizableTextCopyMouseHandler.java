package quiz.ui;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * One shared listener object for all manually-painted text components.
 */
public final class QuizableTextCopyMouseHandler extends MouseAdapter {

    public static final QuizableTextCopyMouseHandler INSTANCE =
            new QuizableTextCopyMouseHandler();

    private QuizableTextCopyMouseHandler() {}

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.isPopupTrigger()) {
            showPopup(e);
            return;
        }

        Object source = e.getSource();

        if (source instanceof QuizableTextRow row) {
            row.beginSelection(e.getPoint());
        } else if (source instanceof QuizableTextBlock block) {
            block.beginSelection(e.getPoint());
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Object source = e.getSource();

        if (source instanceof QuizableTextRow row) {
            row.updateSelection(e.getPoint());
        } else if (source instanceof QuizableTextBlock block) {
            block.updateSelection(e.getPoint());
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (e.isPopupTrigger()) {
            showPopup(e);
            return;
        }

        Object source = e.getSource();

        if (source instanceof QuizableTextRow row) {
            row.endSelection(e.getPoint());
        } else if (source instanceof QuizableTextBlock block) {
            block.endSelection(e.getPoint());
        }
    }

    private void showPopup(MouseEvent e) {
        Object source = e.getSource();

        if (source instanceof QuizableTextRow row) {
            row.showCopyPopup(e.getPoint());
        } else if (source instanceof QuizableTextBlock block) {
            block.showCopyPopup(e.getPoint());
        }
    }
}
