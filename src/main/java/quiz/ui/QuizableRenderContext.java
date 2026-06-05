package quiz.ui;

import quiz.Quizable;
import quiz.QuizablePanelConfig;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public class QuizableRenderContext {
    private final Set<Object> topLevel =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private final Map<Object, JComponent> topLevelComponents =
            new IdentityHashMap<>();

    private final Map<Class<?>, QuizablePanelConfig> classConfigs =
            new HashMap<>();

    public QuizableRenderContext() {
    }

    public QuizableRenderContext(Collection<? extends Quizable> quizables) {
        if (quizables != null) {
            topLevel.addAll(quizables);
        }
    }

    public void addTopLevel(Object object) {
        if (object != null) {
            topLevel.add(object);
        }
    }

    public void addTopLevels(Collection<? extends Quizable> quizables) {
        if (quizables != null) {
            topLevel.addAll(quizables);
        }
    }

    public boolean isTopLevel(Object object) {
        return object != null && topLevel.contains(object);
    }

    public void registerTopLevel(Object object, JComponent component) {
        if (object == null || component == null) {
            return;
        }

        topLevel.add(object);
        topLevelComponents.put(object, component);
    }

    public boolean focusTopLevel(Object object) {
        JComponent component = topLevelComponents.get(object);

        if (component == null) {
            return false;
        }

        Container parent = component.getParent();

        if (parent instanceof JComponent jcParent) {
            jcParent.scrollRectToVisible(component.getBounds());
        } else {
            component.scrollRectToVisible(
                    new Rectangle(0, 0, component.getWidth(), component.getHeight()));
        }

        Window window = SwingUtilities.getWindowAncestor(component);
        if (window != null) {
            window.toFront();
        }

        flash(component);

        return true;
    }

    private void flash(JComponent component) {
        Border old = component.getBorder();

        component.setBorder(
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.ORANGE, 4, true),
                        old));

        Timer timer = new Timer(900, e -> {
            component.setBorder(old);
            component.repaint();
        });

        timer.setRepeats(false);
        timer.start();
    }

    public void putClassConfig(Class<?> cls, QuizablePanelConfig config) {
        if (cls != null && config != null) {
            classConfigs.put(cls, config.copy());
        }
    }

    public QuizablePanelConfig configFor(Class<?> cls) {
        if (cls == null) {
            return null;
        }

        QuizablePanelConfig exact = classConfigs.get(cls);

        if (exact != null) {
            return exact.copy();
        }

        for (Map.Entry<Class<?>, QuizablePanelConfig> e : classConfigs.entrySet()) {
            if (e.getKey().isAssignableFrom(cls)) {
                return e.getValue().copy();
            }
        }

        return null;
    }
}