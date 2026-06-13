package quiz.ui;

import java.util.List;

/**
 * Notified when the set of {@link QuizablePanel} cards controlled by a
 * {@link QuizablePanelView} changes after the view is already showing.
 *
 * {@link QuizableSearchPanel} implements this to re-snapshot the target
 * panel (column count, original order, search index) so that live-added
 * cards are searchable and sortable like the rest.
 *
 * Callbacks fire on the Event Dispatch Thread.
 */
public interface QuizablePanelTargetListener {

    /** Called after the given cards were added to the controlled target. */
    void quizablePanelsAdded(List<QuizablePanel> added);

    /**
     * Called after the given cards were re-rendered in place because their
     * backing quizables changed (e.g. a query log advancing RUNNING → OK).
     * The panel instances are the same; their content is new.
     */
    void quizablePanelsUpdated(List<QuizablePanel> updated);
}
