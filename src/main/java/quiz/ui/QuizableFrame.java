package quiz.ui;

import quiz.Quizable;
import quiz.QuizablePanelConfig;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class QuizableFrame {
    private static final Map<Quizable, WeakReference<JFrame>> openFrames = new IdentityHashMap<>();

    public QuizableFrame(Quizable q, QuizablePanelConfig cfg) {
        this(q.getName(), q, cfg);
    }

    public QuizableFrame(String title, Quizable quizable,
                         QuizablePanelConfig config) {
        if (quizable == null) {
            return;
        }
        JFrame existing = getExistingFrame(quizable);

        if (existing != null) {
            existing.setVisible(true);
            existing.toFront();
            existing.requestFocus();
            return;
        }

        JFrame frame = new JFrame(title);

        openFrames.put(quizable, new WeakReference<>(frame));

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                openFrames.remove(quizable);
            }

            @Override
            public void windowClosing(WindowEvent e) {
                openFrames.remove(quizable);
            }
        });
        // The frame's own title bar already shows the name, so suppress the
        // card's title header to avoid showing the same name twice.
        QuizablePanel panel = new QuizablePanel(quizable, config, true, true);

        JScrollPane scroll = new JScrollPane(panel);
        frame.add(scroll);

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);
    }

    private static JFrame getExistingFrame(Quizable quizable) {
        WeakReference<JFrame> ref = openFrames.get(quizable);

        if (ref == null) {
            return null;
        }

        JFrame frame = ref.get();

        if (frame == null || !frame.isDisplayable()) {
            openFrames.remove(quizable);
            return null;
        }

        return frame;
    }
}