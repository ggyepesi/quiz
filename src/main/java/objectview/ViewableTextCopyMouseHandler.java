package objectview;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * One shared listener object for all manually-painted text components.
 */
public final class ViewableTextCopyMouseHandler extends MouseAdapter {

    public static final ViewableTextCopyMouseHandler INSTANCE =
            new ViewableTextCopyMouseHandler();

    private ViewableTextCopyMouseHandler() {}

    @Override
    public void mousePressed(MouseEvent e) {
        if (e.isPopupTrigger()) {
            showPopup(e);
            return;
        }

        Object source = e.getSource();

        if (source instanceof ViewableTextRow row) {
            row.beginSelection(e.getPoint());
        } else if (source instanceof ViewableTextBlock block) {
            block.beginSelection(e.getPoint());
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        Object source = e.getSource();

        if (source instanceof ViewableTextRow row) {
            row.updateSelection(e.getPoint());
        } else if (source instanceof ViewableTextBlock block) {
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

        if (source instanceof ViewableTextRow row) {
            row.endSelection(e.getPoint());
        } else if (source instanceof ViewableTextBlock block) {
            block.endSelection(e.getPoint());
        }
    }

    private void showPopup(MouseEvent e) {
        Object source = e.getSource();

        if (source instanceof ViewableTextRow row) {
            row.showCopyPopup(e.getPoint());
        } else if (source instanceof ViewableTextBlock block) {
            block.showCopyPopup(e.getPoint());
        }
    }
}
