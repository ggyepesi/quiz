package domain.workbench.execution.app;

import domain.workbench.execution.ui.RunInspectorFrame;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

/** Launches the saved query-log and pipeline inspector without ModelBuilder. */
public final class RunInspectorApp {
    private RunInspectorApp() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            RunInspectorFrame frame = new RunInspectorFrame();
            frame.setVisible(true);
            if (args != null && args.length > 0) frame.open(Path.of(args[0]));
        });
    }
}
