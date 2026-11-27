package process.swing;

import org.junit.jupiter.api.Test;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crowded panel's content moves to a window and back (#585). It is the same component,
 * never a copy, so whatever the reader was doing in it survives the move; closing the
 * window is the same as Back to panel, and the window's size is remembered for the session.
 */
class DetachablePanelTest {

    @Test void theSameContentMovesToAWindowAndBackWithItsState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var typed = new JTextField("half-typed filter");
            var title = new AtomicReference<>("Actions — Field values");
            var panel = new DetachablePanel(typed, title::get, ignored -> { });

            panel.toWindow();
            JDialog window = panel.window();
            assertTrue(panel.isDetached());
            assertSame(window, SwingUtilities.getWindowAncestor(typed),
                    "the same component moved; nothing was copied");
            assertEquals("Actions — Field values", window.getTitle());
            assertNotNull(find(panel, JLabel.class, "Shown in a separate window."),
                    "the panel says where its content went");

            title.set("Actions — Configured field evidence");
            panel.titleChanged();
            assertEquals("Actions — Configured field evidence", window.getTitle());

            Rectangle moved = new Rectangle(40, 50, 700, 500);
            window.setBounds(moved);
            panel.backToPanel();
            assertFalse(panel.isDetached());
            assertTrue(SwingUtilities.isDescendingFrom(typed, panel));
            assertEquals("half-typed filter", typed.getText());
            assertFalse(window.isDisplayable(), "the emptied window is disposed");

            panel.toWindow();
            assertEquals(moved, panel.window().getBounds(),
                    "the window reopens where the reader left it in this session");
            panel.backToPanel();
        });
    }

    @Test void closingTheWindowReturnsTheContentRatherThanLosingIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var content = new JLabel("results");
            var panel = new DetachablePanel(content, () -> "Results", ignored -> { });
            panel.toWindow();
            var window = panel.window();

            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));

            assertFalse(panel.isDetached());
            assertNull(panel.window());
            assertTrue(SwingUtilities.isDescendingFrom(content, panel));
        });
    }

    @Test void returningFromALargerWindowRestoresTheDockedReadingPosition() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var scroll = new JScrollPane(new JTextArea("Line of content\n".repeat(28)));
            var panel = new DetachablePanel(scroll, () -> "Results", JDialog::addNotify);
            var dock = new JFrame("Project");
            dock.setContentPane(panel);
            dock.setSize(900, 260);
            dock.addNotify();
            try {
                dock.validate();
                var viewport = scroll.getViewport();
                var reading = new Point(0, 170);
                viewport.setViewPosition(reading);
                assertEquals(reading, viewport.getViewPosition());

                panel.toWindow();
                panel.window().validate();
                assertEquals(new Point(), viewport.getViewPosition(),
                        "all the content fits in the larger window");
                panel.backToPanel();
                dock.validate();
                assertEquals(reading, viewport.getViewPosition(),
                        "the larger window did not erase the dock's reading position");

                panel.toWindow();
                panel.window().setSize(900, 260);
                panel.window().validate();
                var moved = new Point(0, 80);
                viewport.setViewPosition(moved);
                assertEquals(moved, viewport.getViewPosition());
                panel.backToPanel();
                assertEquals(moved, viewport.getViewPosition(),
                        "a new reading position chosen in the window returns to the dock");
            } finally {
                panel.backToPanel();
                dock.dispose();
            }
        });
    }

    private static <T extends java.awt.Component> T find(
            java.awt.Container root, Class<T> type, String text) {
        for (var child : root.getComponents()) {
            if (type.isInstance(child) && child instanceof JLabel label && text.equals(label.getText())) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container container) {
                T found = find(container, type, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
