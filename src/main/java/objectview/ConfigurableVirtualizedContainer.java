package objectview;

import objectview.viewconfig.ViewConfig;

public interface ConfigurableVirtualizedContainer
        extends VirtualizedContainer {
    void setCardConfig(ViewConfig config);
}
