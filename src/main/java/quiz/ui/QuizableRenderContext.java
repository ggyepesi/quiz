package quiz.ui;

import quiz.Quizable;
import quiz.ui.viewconfig.QuizablePanelConfig;

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

    // Quizable references the user has opened in place (expanded inline
    // instead of shown as a collapsed chip). Keyed by identity so the same
    // target stays in sync wherever it appears in the card.
    private final Set<Object> expanded =
            Collections.newSetFromMap(new IdentityHashMap<>());

    // Back-stack of viewport positions: each focusTopLevel() jump records where
    // the view was before scrolling, so the user can return (see back()).
    private final java.util.Deque<NavMark> backStack = new java.util.ArrayDeque<>();
    private Runnable navChangeListener = () -> {};

    private record NavMark(JViewport viewport, Point position) {}

    public boolean canGoBack() {
        return !backStack.isEmpty();
    }

    /** Notified (on the EDT) whenever the back-stack changes, so a Back button
     *  can update its enabled state. */
    public void setNavChangeListener(Runnable listener) {
        this.navChangeListener = listener == null ? () -> {} : listener;
    }

    /** Scrolls back to the position before the last {@link #focusTopLevel} jump. */
    public boolean back() {
        while (!backStack.isEmpty()) {
            NavMark mark = backStack.pop();
            if (mark.viewport().isShowing()) {
                mark.viewport().setViewPosition(mark.position());
                navChangeListener.run();
                return true;
            }
        }
        navChangeListener.run();
        return false;
    }

    public boolean isExpanded(Object target) {
        return target != null && expanded.contains(target);
    }

    // Collection/map collapse state, keyed by the collection's identity. Unlike
    // reference chips (default collapsed), a collection's default depends on its
    // size (small lists stay open), so this is an explicit override layered over
    // a caller-supplied default rather than plain set membership.
    private final Map<Object, Boolean> collectionExpanded =
            new IdentityHashMap<>();

    public boolean isCollectionExpanded(Object key, boolean defaultExpanded) {
        if (key == null) {
            return defaultExpanded;
        }
        Boolean v = collectionExpanded.get(key);
        return v == null ? defaultExpanded : v;
    }

    /** Flips a collection's expand state (seeding from {@code defaultExpanded}
     *  the first time it is toggled). */
    public void toggleCollectionExpanded(Object key, boolean defaultExpanded) {
        if (key == null) {
            return;
        }
        collectionExpanded.put(key, !isCollectionExpanded(key, defaultExpanded));
    }

    /** Forces a collection's expand state (used by search to reveal a match
     *  hidden inside a collapsed list). */
    public void setCollectionExpanded(Object key, boolean expanded) {
        if (key != null) {
            collectionExpanded.put(key, expanded);
        }
    }

    /** Flips the in-place expand state; returns the new state. */
    public boolean toggleExpanded(Object target) {
        if (target == null) {
            return false;
        }
        if (expanded.remove(target)) {
            return false;
        }
        expanded.add(target);
        return true;
    }

    // When true, single-clicking a reference to an object that is top-level
    // in this context navigates (scrolls to + flashes) its existing card
    // instead of opening a new detail frame. Shared across the views that
    // use this context, which is what makes cross-view navigation work.
    private boolean inPlaceNavigation = false;

    public QuizableRenderContext() {
    }

    public boolean inPlaceNavigation() {
        return inPlaceNavigation;
    }

    public void setInPlaceNavigation(boolean inPlaceNavigation) {
        this.inPlaceNavigation = inPlaceNavigation;
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

        // Remember where we were so the user can come back to this spot.
        JViewport viewport = (JViewport)
                SwingUtilities.getAncestorOfClass(JViewport.class, component);
        if (viewport != null) {
            backStack.push(new NavMark(viewport, viewport.getViewPosition()));
            navChangeListener.run();
        }

        Container parent = component.getParent();

        if (viewport != null && parent != null) {
            // Align the target card's TOP to the viewport top. Plain
            // scrollRectToVisible(card.getBounds()) lands mid-card for a card
            // taller than the viewport.
            Component view = viewport.getView();
            Point pt = SwingUtilities.convertPoint(
                    parent, component.getLocation(), view);
            int maxY = Math.max(0,
                    view.getHeight() - viewport.getExtentSize().height);
            int y = Math.max(0, Math.min(pt.y, maxY));
            viewport.setViewPosition(new Point(viewport.getViewPosition().x, y));
        } else if (parent instanceof JComponent jcParent) {
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