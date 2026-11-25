package domain.workbench.unified.app;

import domain.transform.app.DomainCatalog;
import domain.transform.ui.DomainNavigator;
import domain.workbench.unified.ui.UnifiedWorkbenchPanel;

import javax.swing.JFrame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class UnifiedWorkbenchApp {
    private UnifiedWorkbenchApp() { }

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::modelBacked, "Unified Workbench — saved projects",
                "Open in Unified Workbench", (domain, name) -> {
                    var panel = new UnifiedWorkbenchPanel(domain);
                    var frame = new JFrame("Unified Workbench — " + name);
                    frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    frame.add(panel);
                    frame.addWindowListener(new WindowAdapter() {
                        @Override public void windowClosing(WindowEvent event) {
                            panel.requestClose(frame::dispose);
                        }

                        @Override public void windowClosed(WindowEvent event) { panel.close(); }
                    });
                    frame.setSize(1260, 870);
                    frame.setLocationRelativeTo(null);
                    frame.setVisible(true);
                    return frame;
                });
    }
}
