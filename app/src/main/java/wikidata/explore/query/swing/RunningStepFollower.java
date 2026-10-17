package wikidata.explore.query.swing;

import objectview.render.RenderContext;
import work.LogNode;
import work.LogStatus;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * "Follow running step" in the query log (#398): while it is on, the path to every
 * running step is open, so a live run shows what it is doing now without the reader
 * opening it level by level.
 *
 * <p>It changes inspection state only, and only what it opened itself: a step or list
 * the reader had already opened is left alone, and what this opened returns to the
 * disclosure rule's own state once that step is no longer running, or when following
 * is switched off — it forgets its choice rather than writing "folded", so a list the
 * view opens with its owner stays open.
 */
final class RunningStepFollower {

    private final Set<Object> openedCards = identitySet();
    private final Set<Object> openedNodes = identitySet();
    private final Set<Object> openedLists = identitySet();

    /** Opens the path to {@code root}'s running steps and folds what no longer runs.
     *  Returns true when anything changed, so the caller rebuilds the card once. */
    boolean follow(LogNode root, RenderContext context) {
        Set<Object> cards = identitySet();
        Set<Object> nodes = identitySet();
        Set<Object> lists = identitySet();
        if (running(root)) {
            cards.add(root);
            collect(root, nodes, lists);
        }
        boolean changed = fold(openedCards, cards, context::clearChoice)
                | fold(openedNodes, nodes, context::clearChoice)
                | fold(openedLists, lists, context::clearChoice);
        for (Object card : cards) {
            if (!context.isCardExpanded(card) && context.setExpanded(card, true)) {
                openedCards.add(card);
                changed = true;
            }
        }
        for (Object node : nodes) {
            if (!context.isExpanded(node, false) && context.setExpanded(node, true)) {
                openedNodes.add(node);
                changed = true;
            }
        }
        for (Object list : lists) {
            if (!context.isCollectionExpanded(list, false)) {
                context.setCollectionExpanded(list, true);
                openedLists.add(list);
                changed = true;
            }
        }
        return changed;
    }

    /** Switching following off folds everything it opened. */
    boolean stop(RenderContext context) {
        boolean changed = !openedCards.isEmpty() || !openedNodes.isEmpty()
                || !openedLists.isEmpty();
        openedCards.forEach(context::clearChoice);
        openedNodes.forEach(context::clearChoice);
        openedLists.forEach(context::clearChoice);
        forget();
        return changed;
    }

    /** Forgets what it opened without touching any view — the view is gone (a project
     *  switch closes the log window). */
    void forget() {
        openedCards.clear();
        openedNodes.clear();
        openedLists.clear();
    }

    /** Every running step below {@code node}, opened, with the list that holds it. */
    private static void collect(LogNode node, Set<Object> nodes, Set<Object> lists) {
        for (LogNode step : node.steps()) {
            if (!running(step)) continue;
            lists.add(node.steps());
            nodes.add(step);
            collect(step, nodes, lists);
        }
    }

    /** Folds what this opened earlier and no longer needs open. */
    private static boolean fold(Set<Object> opened, Set<Object> needed,
                                java.util.function.Consumer<Object> close) {
        boolean changed = false;
        for (java.util.Iterator<Object> it = opened.iterator(); it.hasNext(); ) {
            Object key = it.next();
            if (needed.contains(key)) continue;
            close.accept(key);
            it.remove();
            changed = true;
        }
        return changed;
    }

    private static boolean running(LogNode node) {
        return node != null && node.status() == LogStatus.RUNNING;
    }

    private static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
