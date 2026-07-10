package quiz.ui;

import quiz.ui.viewconfig.QuizablePanelConfig;

/**
 * Optional capability for a virtual/data-backed container that owns the
 * QuizablePanel factory and can lazily rebuild materialized cards when the
 * view configuration changes.
 */
public interface ConfigurableVirtualizedQuizableContainer
        extends VirtualizedQuizableContainer {

    void setCardConfig(QuizablePanelConfig config);
}
