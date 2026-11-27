package domain.workbench.unified.app;

import domain.DomainModel;
import domain.transform.app.DomainCatalog;
import domain.transform.ui.DomainNavigator;
import domain.workbench.execution.ui.InstancesWindow;
import domain.workbench.execution.ui.SwingQuerySession;
import domain.workbench.ui.WikidataLabelLookup;
import domain.workbench.unified.ui.UnifiedWorkbenchPanel;
import objectview.utils.swing.SwingWindowActivation;
import process.swing.Dialogs;

import javax.swing.JDialog;
import javax.swing.JFrame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class UnifiedWorkbenchApp {
    private UnifiedWorkbenchApp() { }

    public static void main(String[] args) {
        DomainNavigator.show(DomainCatalog::modelBacked, "Unified Workbench — saved projects",
                "Open in Unified Workbench", UnifiedWorkbenchApp::openFrame);
    }

    public static JFrame openFrame(DomainModel domain, String name) {
        var queries = SwingQuerySession.openWorkbench();
        UnifiedWorkbenchPanel panel;
        try {
            panel = new UnifiedWorkbenchPanel(domain, queries);
        } catch (RuntimeException failure) {
            queries.close();
            throw failure;
        }
        WikidataLabelLookup.install(queries.runner());
        var frame = new JFrame("Unified Workbench — " + name);
        // Instances get a window of their own, as in ModelBuilder; it belongs to
        // this project's frame and closes with it.
        var instancesWindow = new JFrame[1];
        panel.onInstancesShown(shown -> {
            if (instancesWindow[0] == null && !shown.reveal()) return;
            if (instancesWindow[0] == null) {
                instancesWindow[0] = InstancesWindow.create(panel.instancesView(), null);
            }
            instancesWindow[0].setTitle(shown.title());
            if (shown.reveal()) SwingWindowActivation.showAndFocus(instancesWindow[0]);
        });
        var transformWindow = new JDialog[1];
        panel.onTransformShown(() -> {
            if (transformWindow[0] == null) {
                var window = Dialogs.workbenchToolWindow(frame, "Transform — " + name);
                window.setContentPane(panel.transformView());
                window.setSize(1150, 800);
                window.setLocationRelativeTo(frame);
                transformWindow[0] = window;
            }
            SwingWindowActivation.showAndFocus(transformWindow[0]);
        });
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(panel);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                panel.requestClose(frame::dispose);
            }

            @Override public void windowClosed(WindowEvent event) {
                if (instancesWindow[0] != null) instancesWindow[0].dispose();
                if (transformWindow[0] != null) transformWindow[0].dispose();
                panel.close();
                queries.close();
            }
        });
        frame.setSize(1260, 870);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        return frame;
    }
}
