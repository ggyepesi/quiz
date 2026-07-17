package aux;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.prefs.Preferences;

public class RememberingFileChooser {
    private final Component parent;
    private final String preferenceKey;
    private final Preferences preferences;

    private File lastDirectory;

    public RememberingFileChooser(Component parent, String preferenceKey) {
        this.parent = parent;
        this.preferenceKey = preferenceKey;
        this.preferences =
                Preferences.userNodeForPackage(RememberingFileChooser.class);

        String saved = preferences.get(preferenceKey, null);

        if (saved != null) {
            File dir = new File(saved);
            if (dir.isDirectory()) {
                lastDirectory = dir;
            }
        }
    }

    public File chooseOpenFile() {
        JFileChooser chooser = newChooser();

        int result = chooser.showOpenDialog(parent);

        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        remember(chooser.getSelectedFile());
        return chooser.getSelectedFile();
    }

    public File chooseSaveFile(String suggestedFilename) {
        JFileChooser chooser = newChooser();

        if (suggestedFilename != null && !suggestedFilename.isBlank()) {
            chooser.setSelectedFile(new File(suggestedFilename));
        }

        int result = chooser.showSaveDialog(parent);

        if (result != JFileChooser.APPROVE_OPTION) {
            return null;
        }

        remember(chooser.getSelectedFile());
        return chooser.getSelectedFile();
    }

    private JFileChooser newChooser() {
        return lastDirectory == null
                ? new JFileChooser()
                : new JFileChooser(lastDirectory);
    }

    private void remember(File file) {
        if (file == null) {
            return;
        }

        File dir = file.isDirectory()
                ? file
                : file.getParentFile();

        if (dir == null) {
            return;
        }

        lastDirectory = dir;
        preferences.put(preferenceKey, dir.getAbsolutePath());
    }
}