package domain.workbench.ui;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.Graphics;
import java.awt.Component;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A result deck whose navigation is owned by {@link WorkbenchActionResultNavigator}.
 *
 * <p>It deliberately keeps the {@link JTabbedPane} selection model and component API used
 * by existing result producers, but paints no second horizontal tab navigator. The action
 * tree is the one visible route between result views.</p>
 */
public final class WorkbenchResultViews extends JTabbedPane {
    private final Map<Component, Runnable> initializers = new IdentityHashMap<>();

    public WorkbenchResultViews() {
        setName("workbench.result.views");
    }

    public void addLazyTab(String title, JComponent view, Runnable initialize) {
        initializers.put(view, initialize);
        addTab(title, view);
    }

    void revealTab(int index) {
        if (index < 0) return;
        setSelectedIndex(index);
        Runnable initialize = initializers.remove(getComponentAt(index));
        if (initialize != null) initialize.run();
    }

    @Override public void removeTabAt(int index) {
        initializers.remove(getComponentAt(index));
        super.removeTabAt(index);
    }

    @Override public void updateUI() {
        setUI(new TablessUi());
    }

    private static final class TablessUi extends BasicTabbedPaneUI {
        @Override protected int calculateTabAreaHeight(
                int placement, int horizontalRuns, int maximumTabHeight) {
            return 0;
        }

        @Override protected int calculateTabAreaWidth(
                int placement, int verticalRuns, int maximumTabWidth) {
            return 0;
        }

        @Override protected void paintTabArea(Graphics graphics,
                int placement, int selectedIndex) {
            // The workbench tree paints the result navigation.
        }
    }
}
