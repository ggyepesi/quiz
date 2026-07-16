package objectview;

import quiz.Quizable;

import javax.swing.JComponent;
import java.util.List;

public interface VirtualizedQuizableContainer {
    List<Quizable> items();
    Quizable topVisibleItem();
    JComponent navigateToTop(Quizable item);
    void setItems(List<Quizable> orderedItems);
}
