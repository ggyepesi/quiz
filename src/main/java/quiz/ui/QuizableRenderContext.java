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

    // When the view is virtualized, a top-level target may not have a live card
    // (it's off-screen). A resolver builds + positions it on demand so navigation
    // (focusTopLevel) can still scroll to it. There is one resolver PER virtualized
    // section (a MultiQuizableView shares one context across several sections), so
    // focusTopLevel tries each until one owns the target.
    private final java.util.List<java.util.function.Function<Object, JComponent>>
            topLevelResolvers = new java.util.ArrayList<>();

    public void addTopLevelResolver(
            java.util.function.Function<Object, JComponent> resolver) {
        if (resolver != null) {
            topLevelResolvers.add(resolver);
        }
    }

    private JComponent resolveTopLevel(Object object) {
        for (java.util.function.Function<Object, JComponent> resolver
                : topLevelResolvers) {
            JComponent c = resolver.apply(object);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

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

    private static final boolean NAV_DEBUG = Boolean.getBoolean("quiz.nav.debug");

    public boolean focusTopLevel(Object object) {
        JComponent fromMap = topLevelComponents.get(object);
        JComponent component = fromMap;

        // A cached top-level component can be STALE: virtualization recycles
        // off-screen cards (their parent becomes null) but the map entry lingers.
        // Using it would leave component.getParent() == null, so navigateToTop is
        // never reached and the click silently does nothing. Discard a detached
        // one and resolve the object fresh in its owning list (object-centric).
        if (component != null && component.getParent() == null) {
            component = null;
        }

        // Virtualized view: the target card isn't built (off-screen) — build +
        // position it on demand so we can scroll to it.
        if (component == null) {
            component = resolveTopLevel(object);
        }

        if (NAV_DEBUG) {
            System.err.println("[nav] focusTopLevel object='"
                    + (object instanceof Quizable q ? q.getDisplayName() : object)
                    + "' fromMap=" + (fromMap != null)
                    + " resolved=" + (component != null)
                    + " parent=" + (component == null || component.getParent() == null
                            ? "-" : component.getParent().getClass().getSimpleName()));
        }

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

        if (parent instanceof VirtualizedCardList vcl
                && object instanceof Quizable q) {
            // Variable-height virtualization: the card's absolute offset shifts as
            // scrolling measures its neighbours, so delegate to a stabilizing scroll
            // (a plain setViewPosition lands a few cards off, intermittently).
            JComponent settled = vcl.navigateToTop(q);
            if (settled != null) {
                component = settled;
            }
        } else if (viewport != null && parent != null) {
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

    // -------- Collapsible root cards (birdseye browse) --------
    // When on, each root card renders collapsed (name + a toggle triangle) and
    // expands on demand — a birdseye list you drill into at will. The per-card
    // open state is keyed by identity so it survives virtualized rebuilds. A
    // toggle flips the state and asks the view (via a handler) to rebuild that
    // one card FRESH at its new size — never grown in place.
    private boolean collapsibleCards = false;
    private final Map<Object, Boolean> cardExpanded = new IdentityHashMap<>();
    private final java.util.List<java.util.function.Consumer<Quizable>> cardToggleHandlers =
            new java.util.ArrayList<>();

    public boolean collapsibleCards() {
        return collapsibleCards;
    }

    public void setCollapsibleCards(boolean collapsibleCards) {
        this.collapsibleCards = collapsibleCards;
    }

    public boolean isCardExpanded(Object key, boolean defaultExpanded) {
        if (key == null) {
            return defaultExpanded;
        }
        Boolean v = cardExpanded.get(key);
        return v == null ? defaultExpanded : v;
    }

    public void toggleCardExpanded(Object key, boolean defaultExpanded) {
        if (key != null) {
            cardExpanded.put(key, !isCardExpanded(key, defaultExpanded));
        }
    }

    /** Registers a handler (the view) that rebuilds a single card after its
     *  collapse/expand state changed. Additive so a shared context can drive
     *  several virtualized sections; each rebuilds only the card it owns. */
    public void addCardToggleHandler(java.util.function.Consumer<Quizable> handler) {
        if (handler != null) {
            cardToggleHandlers.add(handler);
        }
    }

    public void notifyCardToggled(Quizable q) {
        for (java.util.function.Consumer<Quizable> handler : cardToggleHandlers) {
            handler.accept(q);
        }
    }

    // -------- Single card selection --------
    // One selected object across all cards of this view. Like the search
    // highlight, it's data-based (a rebuilt card re-reads isSelected), so it
    // survives virtualization. Listeners let an app (e.g. curation) act on the
    // selected object.
    private boolean selectionEnabled = false;
    private Object selected;
    private final java.util.List<java.util.function.Consumer<Object>> selectionListeners =
            new java.util.ArrayList<>();

    public boolean selectionEnabled() {
        return selectionEnabled;
    }

    public void setSelectionEnabled(boolean selectionEnabled) {
        this.selectionEnabled = selectionEnabled;
    }

    public boolean isSelected(Object object) {
        return object != null && object == selected;
    }

    public Object selected() {
        return selected;
    }

    public void addSelectionListener(java.util.function.Consumer<Object> listener) {
        if (listener != null) {
            selectionListeners.add(listener);
        }
    }

    /** Selects {@code object}, repainting the previously- and newly-selected
     *  cards (if built) and notifying listeners. */
    public void select(Object object) {
        if (object == selected) {
            return;
        }
        Object previous = selected;
        selected = object;
        repaintCard(previous);
        repaintCard(object);
        for (java.util.function.Consumer<Object> listener : selectionListeners) {
            listener.accept(object);
        }
    }

    private void repaintCard(Object object) {
        JComponent c = topLevelComponents.get(object);
        if (c != null && c.getParent() != null) {
            c.repaint();
        }
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