package objectview;

import objectview.Viewable;

import javax.swing.JComponent;
import java.util.List;

public interface VirtualizedQuizableContainer {
    List<Viewable> items();
    Viewable topVisibleItem();
    JComponent navigateToTop(Viewable item);
    void setItems(List<Viewable> orderedItems);
}
