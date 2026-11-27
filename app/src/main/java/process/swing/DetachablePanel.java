package process.swing;

import objectview.utils.swing.SwingWindowActivation;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A panel whose content can be moved to a window and back when it is too crowded where
 * it is docked.
 *
 * <p>The content is MOVED, never copied: the same component is reparented, so its
 * selection, scroll position, running work and results survive, and a result is never
 * shown in two places at once. While the content is in its window, the panel shows a
 * placeholder that can bring it back; closing the window also brings it back. The window
 * comes from {@link Dialogs#workbenchToolWindow}, so it belongs to the project window and
 * closes with it. Its size and position are remembered for this panel's lifetime only
 * (per session), never persisted.</p>
 */
public final class DetachablePanel extends JPanel {

    static final String TO_WINDOW = "To window";
    static final String BACK_TO_PANEL = "Back to panel";

    private final JComponent content;
    private final Supplier<String> title;
    private final Consumer<JDialog> opener;
    private final JPanel docked = new JPanel(new BorderLayout());
    private final JPanel placeholder = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
    private final JButton toWindow = new JButton("⇱ " + TO_WINDOW);
    private JDialog window;
    private Rectangle lastBounds;
    private List<ScrollPosition> dockedScroll = List.of();

    public DetachablePanel(JComponent content, Supplier<String> title) {
        this(content, title, SwingWindowActivation::showAndFocus);
    }

    /** {@code opener} shows the window; tests supply one that does not. */
    DetachablePanel(JComponent content, Supplier<String> title, Consumer<JDialog> opener) {
        super(new BorderLayout());
        this.content = Objects.requireNonNull(content, "content");
        this.title = title == null ? () -> "" : title;
        this.opener = Objects.requireNonNull(opener, "opener");
        setName("detachable");

        toWindow.setName("detachable.toWindow");
        toWindow.setToolTipText("Move this to its own window; Back to panel returns it here.");
        toWindow.setMargin(new java.awt.Insets(0, 6, 0, 6));
        toWindow.addActionListener(event -> toWindow());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        bar.add(toWindow);
        docked.add(bar, BorderLayout.NORTH);
        docked.add(content, BorderLayout.CENTER);

        JButton back = new JButton(BACK_TO_PANEL);
        back.setName("detachable.placeholder.back");
        back.addActionListener(event -> backToPanel());
        JButton raise = new JButton("Show window");
        raise.addActionListener(event -> { if (window != null) opener.accept(window); });
        placeholder.setName("detachable.placeholder");
        placeholder.add(new JLabel("Shown in a separate window."));
        placeholder.add(raise);
        placeholder.add(back);

        add(docked, BorderLayout.CENTER);
    }

    public boolean isDetached() { return window != null; }

    /** The window's title follows what the content currently shows. */
    public void titleChanged() {
        if (window != null) window.setTitle(windowTitle());
    }

    /** Moves the content into its window, creating the window on first use. */
    public void toWindow() {
        if (window != null) {
            opener.accept(window);
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = Dialogs.workbenchToolWindow(owner, windowTitle());
        JButton back = new JButton(BACK_TO_PANEL);
        back.setName("detachable.window.back");
        back.addActionListener(event -> backToPanel());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        bar.add(back);
        JPanel pane = new JPanel(new BorderLayout());
        pane.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        pane.add(bar, BorderLayout.NORTH);
        dockedScroll = scrollPositions();
        docked.remove(content);
        pane.add(content, BorderLayout.CENTER);
        dialog.setContentPane(pane);
        // Closing the window is the same as Back to panel: nothing is ever lost with it.
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { backToPanel(); }
        });
        if (lastBounds != null) {
            dialog.setBounds(lastBounds);
        } else {
            Dimension size = content.getSize();
            dialog.setSize(Math.max(900, size.width + 40), Math.max(650, size.height + 80));
            dialog.setLocationRelativeTo(owner);
        }
        window = dialog;
        swap(placeholder);
        opener.accept(dialog);
        restoreScroll(dialog, dockedScroll);
    }

    /** Returns the content to this panel and disposes its window. */
    public void backToPanel() {
        if (window == null) return;
        JDialog closing = window;
        window = null;
        lastBounds = closing.getBounds();
        List<ScrollPosition> returning = scrollPositions().stream()
                .map(position -> position.returningFrom(dockedScroll))
                .toList();
        dockedScroll = List.of();
        closing.getContentPane().remove(content);
        docked.add(content, BorderLayout.CENTER);
        swap(docked);
        closing.dispose();
        restoreScroll(SwingUtilities.getWindowAncestor(this), returning);
    }

    private List<ScrollPosition> scrollPositions() {
        List<ScrollPosition> positions = new ArrayList<>();
        captureScroll(content, positions);
        return positions;
    }

    private static void captureScroll(Component component, List<ScrollPosition> positions) {
        if (component instanceof JViewport viewport && viewport.getView() != null) {
            positions.add(new ScrollPosition(viewport, viewport.getView(), viewport.getViewPosition()));
        }
        if (component instanceof Container parent) {
            for (Component child : parent.getComponents()) captureScroll(child, positions);
        }
    }

    private static void restoreScroll(Window host, List<ScrollPosition> positions) {
        if (host == null || !host.isDisplayable() || positions.isEmpty()) return;
        host.validate();
        for (var position : positions) {
            if (position.viewport().getView() == position.view()) {
                position.viewport().setViewPosition(position.clamped());
            }
        }
    }

    private record ScrollPosition(JViewport viewport, Component view, Point point) {
        Point clamped() {
            Dimension size = viewport.getViewSize();
            Dimension extent = viewport.getExtentSize();
            return new Point(Math.clamp(point.x, 0, Math.max(0, size.width - extent.width)),
                    Math.clamp(point.y, 0, Math.max(0, size.height - extent.height)));
        }

        ScrollPosition returningFrom(List<ScrollPosition> docked) {
            for (var original : docked) {
                if (original.viewport != viewport || original.view != view) continue;
                // The larger host may only be able to show a clamped position (even
                // zero when everything fits). Keep the original on axes the reader
                // did not move away from that clamped position.
                Point bound = original.clamped();
                return new ScrollPosition(viewport, view, new Point(
                        point.x == bound.x ? original.point.x : point.x,
                        point.y == bound.y ? original.point.y : point.y));
            }
            return this;
        }
    }

    private void swap(JComponent shown) {
        removeAll();
        add(shown, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private String windowTitle() {
        String text = title.get();
        return text == null || text.isBlank() ? "Detached view" : text;
    }

    JDialog window() { return window; }
}
