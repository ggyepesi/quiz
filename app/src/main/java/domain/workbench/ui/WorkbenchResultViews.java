package domain.workbench.ui;

import javax.swing.JTabbedPane;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.Graphics;

/**
 * A result deck whose navigation is owned by {@link WorkbenchActionResultNavigator}.
 *
 * <p>It deliberately keeps the {@link JTabbedPane} selection model and component API used
 * by existing result producers, but paints no second horizontal tab navigator. The action
 * tree is the one visible route between result views.</p>
 */
public final class WorkbenchResultViews extends JTabbedPane {

    public WorkbenchResultViews() {
        setName("workbench.result.views");
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
