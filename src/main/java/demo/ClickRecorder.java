package demo;

import objectview.Card;

import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Records mouse press/release events (as absolute screen coordinates + timing)
 * inside a scope component, so an interaction can be replayed faithfully in a
 * fresh instance with {@link ClickReplayer}. A debugging aid for reproducing the
 * intermittent reference-navigation failure — see {@link NavDebugDemo}.
 */
public final class ClickRecorder {

    /** id is {@link MouseEvent#MOUSE_PRESSED} or MOUSE_RELEASED; label is the name
     *  of the card that was clicked (for a human-readable, verifiable trace). */
    public record Click(long t, int x, int y, int id, String label) {}

    private final List<Click> clicks = new ArrayList<>();
    private AWTEventListener listener;
    private Component scope;
    private long startTime;
    private volatile boolean recording;

    /** Starts recording press/release events whose source is within {@code scope}
     *  (so the record/stop toolbar buttons aren't captured). */
    public void start(Component scope) {
        this.scope = scope;
        clicks.clear();
        startTime = System.currentTimeMillis();
        listener = e -> {
            if (!recording || !(e instanceof MouseEvent me)) {
                return;
            }
            int id = me.getID();
            if (id != MouseEvent.MOUSE_PRESSED && id != MouseEvent.MOUSE_RELEASED) {
                return;
            }
            Component src = me.getComponent();
            if (src == null || (scope != null && !SwingUtilities.isDescendingFrom(src, scope))) {
                return;
            }
            Point p = me.getLocationOnScreen();
            clicks.add(new Click(System.currentTimeMillis() - startTime, p.x, p.y, id,
                    describe(src)));
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK);
        recording = true;
    }

    public void stop() {
        recording = false;
        if (listener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            listener = null;
        }
    }

    public int size() {
        return clicks.size();
    }

    public void save(File file) throws IOException {
        try (PrintWriter w = new PrintWriter(file)) {
            for (Click c : clicks) {
                // label last so it can contain nothing that breaks the 4 leading
                // numeric columns; commas in a name are unlikely for card titles.
                w.println(c.t() + "," + c.x() + "," + c.y() + "," + c.id()
                        + "," + (c.label() == null ? "" : c.label()));
            }
        }
    }

    // The name of the card that was clicked (the Card the click lands in),
    // so the recording reads like "clicked Alpha-1" — verifiable and diffable.
    private static String describe(Component c) {
        for (Component cur = c; cur != null; cur = cur.getParent()) {
            if (cur instanceof Card qp
                    && qp.getViewable() != null) {
                return qp.getViewable().getName();
            }
        }
        return c == null ? "?" : c.getClass().getSimpleName();
    }
}
