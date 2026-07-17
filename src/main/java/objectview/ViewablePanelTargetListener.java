package objectview;

import java.util.List;

/**
 * Notified when the set of {@link ViewablePanel} cards controlled by a
 * {@link ViewablePanelView} changes after the view is already showing.
 *
 * {@link ViewableSearchPanel} implements this to re-snapshot the target
 * panel (column count, original order, search index) so that live-added
 * cards are searchable and sortable like the rest.
 *
 * Callbacks fire on the Event Dispatch Thread.
 */
public interface ViewablePanelTargetListener {

    /** Called after the given cards were added to the controlled target. */
    void quizablePanelsAdded(List<ViewablePanel> added);

    /**
     * Called after the given cards were re-rendered in place because their
     * backing quizables changed (e.g. a query log advancing RUNNING → OK).
     * The panel instances are the same; their content is new.
     */
    void quizablePanelsUpdated(List<ViewablePanel> updated);

    /**
     * Called when a single card is (re)materialized during virtualized scrolling —
     * so a listener can re-apply transient decoration a freshly-built card lacks,
     * e.g. the search highlight (otherwise lost when a card is virtualized out and
     * rebuilt on scroll-back).
     */
    default void quizablePanelMaterialized(ViewablePanel card) {
    }
}
