package wikidata.explore.query.swing;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

/** Launches the saved query-log and pipeline inspector without ModelBuilder. */
public final class RunInspectorMain {
    private RunInspectorMain() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            RunInspectorFrame frame = new RunInspectorFrame();
            frame.setVisible(true);
            if (args != null && args.length > 0) frame.open(Path.of(args[0]));
        });
    }
}
