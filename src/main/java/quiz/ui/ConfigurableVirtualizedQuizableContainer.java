package quiz.ui;

import quiz.ui.viewconfig.QuizablePanelConfig;

public interface ConfigurableVirtualizedQuizableContainer
        extends VirtualizedQuizableContainer {
    void setCardConfig(QuizablePanelConfig config);
}
