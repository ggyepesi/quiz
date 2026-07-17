package objectview;

import objectview.viewconfig.ViewablePanelConfig;

public interface ConfigurableVirtualizedQuizableContainer
        extends VirtualizedQuizableContainer {
    void setCardConfig(ViewablePanelConfig config);
}
