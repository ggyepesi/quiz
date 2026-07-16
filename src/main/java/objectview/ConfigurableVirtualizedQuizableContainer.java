package objectview;

import objectview.viewconfig.QuizablePanelConfig;

public interface ConfigurableVirtualizedQuizableContainer
        extends VirtualizedQuizableContainer {
    void setCardConfig(QuizablePanelConfig config);
}
