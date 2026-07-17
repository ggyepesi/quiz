package objectview.viewconfig;

import objectview.Viewable;
import objectview.ViewablePanel;

import javax.swing.*;
import java.awt.*;

public class ViewablePanelConfigEditorFrame extends JFrame {

    private final Viewable sample;

    private final JPanel editorHolder = new JPanel(new BorderLayout());
    private final JPanel previewPanel = new JPanel(new BorderLayout());

    private ViewablePanelConfigEditor editor;

    public ViewablePanelConfigEditorFrame(Viewable sample,
                                          ViewablePanelConfig initialConfig) {
        super("View Config Editor - " + sample.getClass().getSimpleName());

        this.sample = sample;

        ViewablePanelConfig config = initialConfig == null
                ? ViewablePanelConfig.of(sample.getClass())
                : initialConfig.copy();

        this.editor = createEditor(config);
        editorHolder.add(editor, BorderLayout.CENTER);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                editorHolder,
                previewPanel
        );
        split.setResizeWeight(0.40);

        add(split, BorderLayout.CENTER);
        add(buttonPanel(), BorderLayout.SOUTH);

        refreshPreview();

        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    private ViewablePanelConfigEditor createEditor(ViewablePanelConfig config) {
        ViewablePanelConfigEditor e = new ViewablePanelConfigEditor(config);
        e.setChangeListener(this::refreshPreview);
        return e;
    }

    private JPanel buttonPanel() {
        JButton saveJson = new JButton("Save JSON");
        saveJson.addActionListener(e -> saveJson());

        JButton reset = new JButton("Reset to Defaults");
        reset.addActionListener(e -> resetToDefaults());

        JButton refresh = new JButton("Refresh Preview");
        refresh.addActionListener(e -> refreshPreview());

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());

        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        p.add(saveJson);
        p.add(reset);
        p.add(refresh);
        p.add(close);
        return p;
    }

    private void saveJson() {
        ViewablePanelConfig config = editor.getConfig();
        config.clearCache();

        java.io.File file =
                ViewablePanelConfigJsonIO.defaultFileFor(sample.getClass());

        ViewablePanelConfigJsonIO.save(file, config);

        JOptionPane.showMessageDialog(
                this,
                "Saved:\n" + file.getAbsolutePath(),
                "View config saved",
                JOptionPane.INFORMATION_MESSAGE
                                     );
    }

    private void resetToDefaults() {
        ViewablePanelConfig defaultConfig =
                ViewablePanelConfig.of(sample.getClass());

        editorHolder.removeAll();

        editor = createEditor(defaultConfig);
        editorHolder.add(editor, BorderLayout.CENTER);

        editorHolder.revalidate();
        editorHolder.repaint();

        refreshPreview();
    }

    private void refreshPreview() {
        ViewablePanelConfig config = editor.getConfig();
        config.clearCache();

        previewPanel.removeAll();

        ViewablePanel panel = new ViewablePanel(sample, config, true);
        previewPanel.add(new JScrollPane(panel), BorderLayout.CENTER);

        previewPanel.revalidate();
        previewPanel.repaint();
    }

    public ViewablePanelConfig getConfig() {
        return editor.getConfig();
    }
}