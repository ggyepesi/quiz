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
        System.out.println("QuizableFrame...");
        JFrame existing = getExistingFrame(quizable);

        if (existing != null) {
            existing.setVisible(true);
            existing.toFront();
            existing.requestFocus();
            return;
        }
        System.out.println("QuizableFrame1...");

        JFrame frame = new JFrame(title);

        openFrames.put(quizable, new WeakReference<>(frame));
        System.out.println("QuizableFrame2...");

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
        System.out.println("QuizableFrame.3.." + quizable.getClass() + ", " + quizable.getName());
        System.out.println("F3a");
        try {
            QuizablePanel.dumpFields(quizable);
            Field f = quizable.getClass().getDeclaredField("n");
            f.setAccessible(true);

            List<?> list = (List<?>) f.get(quizable);

            for (Object x : list) {
                System.out.println("  n item class=" + x.getClass().getName()
                                           + " name=" + ((Quizable) x).getName()
                                           + " id=" + System.identityHashCode(x));
            }
        } catch (Exception ex) {
            //throw new RuntimeException(ex);
        }
        QuizablePanel panel = new QuizablePanel(quizable, config, true);
        System.out.println("F3b");

        JScrollPane scroll = new JScrollPane(panel);
        System.out.println("F3c");

        frame.add(scroll);
        System.out.println("F3d");

        System.out.println("QuizableFrame4...");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);
        System.out.println("QuizableFrame5...");

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