package wikidata.explore.query.swing;

import process.ProcessWorkflowPipeline;
import process.swing.workflow.ProcessWorkflowPipelinePanel;
import process.SavedRunArtifact;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;
import java.nio.file.Path;

/** Standalone, read-only viewer for saved query logs and pipeline snapshots. */
public final class RunInspectorFrame extends JFrame {
    private final JComboBox<String> pipelines = new JComboBox<>();
    private final JPanel pipelineHost = new JPanel(new BorderLayout());
    private final JTextArea log = new JTextArea();
    private final JTextField search = new JTextField(26);
    private final JLabel matchCount = new JLabel(" ");
    private final Path preferredDirectory;
    private SavedRunArtifact artifact;

    public RunInspectorFrame() {
        this(Path.of(aux.Constants.dataDirectory));
    }

    public RunInspectorFrame(Path preferredDirectory) {
        super("Run Inspector");
        this.preferredDirectory = usableDirectory(preferredDirectory);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));

        JButton open = new JButton("Open saved query log…");
        open.addActionListener(e -> chooseAndOpen(this));
        pipelines.addActionListener(e -> showPipeline(pipelines.getSelectedIndex()));
        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.add(open, BorderLayout.WEST);
        top.add(pipelines, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel logPanel = new JPanel(new BorderLayout(5, 5));
        JPanel find = new JPanel(new BorderLayout(5, 5));
        find.add(new JLabel("Find:"), BorderLayout.WEST);
        find.add(search, BorderLayout.CENTER);
        find.add(matchCount, BorderLayout.EAST);
        logPanel.add(find, BorderLayout.NORTH);
        logPanel.add(new JScrollPane(log), BorderLayout.CENTER);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { highlight(); }
            @Override public void removeUpdate(DocumentEvent e) { highlight(); }
            @Override public void changedUpdate(DocumentEvent e) { highlight(); }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Pipeline", pipelineHost);
        tabs.addTab("Log", logPanel);
        add(tabs, BorderLayout.CENTER);
        setSize(1100, 760);
        setLocationByPlatform(true);
    }

    public void open(Path path) {
        try {
            artifact = SavedRunArtifact.read(path);
            log.setText(artifact.logText());
            log.setCaretPosition(0);
            pipelines.removeAllItems();
            for (SavedRunArtifact.PipelineRun run : artifact.pipelines()) {
                pipelines.addItem(run.title());
            }
            if (artifact.pipelines().isEmpty()) {
                pipelineHost.removeAll();
                pipelineHost.add(new JLabel("No pipeline snapshot is present in this log.",
                        SwingConstants.CENTER));
            } else {
                pipelines.setSelectedIndex(0);
                showPipeline(0);
            }
            setTitle("Run Inspector — " + path.getFileName());
            highlight();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Could not open the saved run: " + ex.getMessage(),
                    "Open failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void chooseAndOpen(Component parent) {
        JFileChooser chooser = new JFileChooser(preferredDirectory.toFile());
        chooser.setDialogTitle("Open saved query log");
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            open(chooser.getSelectedFile().toPath());
        }
    }

    static Path usableDirectory(Path preferred) {
        Path candidate = preferred == null ? null
                : preferred.toAbsolutePath().normalize();
        if (candidate != null && java.nio.file.Files.isDirectory(candidate)) {
            return candidate;
        }
        return Path.of(aux.Constants.dataDirectory).toAbsolutePath().normalize();
    }

    private void showPipeline(int index) {
        if (artifact == null || index < 0 || index >= artifact.pipelines().size()) return;
        ProcessWorkflowPipeline restored = ProcessWorkflowPipeline.restored(
                artifact.pipelines().get(index).phases());
        pipelineHost.removeAll();
        pipelineHost.add(new ProcessWorkflowPipelinePanel(restored), BorderLayout.CENTER);
        pipelineHost.revalidate();
        pipelineHost.repaint();
    }

    private void highlight() {
        var highlighter = log.getHighlighter();
        highlighter.removeAllHighlights();
        String needle = search.getText();
        if (needle == null || needle.isBlank()) {
            matchCount.setText(" ");
            return;
        }
        String haystack = log.getText().toLowerCase(java.util.Locale.ROOT);
        String wanted = needle.toLowerCase(java.util.Locale.ROOT);
        int at = 0;
        int matches = 0;
        int first = -1;
        while ((at = haystack.indexOf(wanted, at)) >= 0) {
            if (first < 0) first = at;
            try {
                highlighter.addHighlight(at, at + wanted.length(),
                        new DefaultHighlighter.DefaultHighlightPainter(
                                new Color(255, 230, 120)));
            } catch (javax.swing.text.BadLocationException ignored) { }
            matches++;
            at += Math.max(1, wanted.length());
        }
        matchCount.setText(matches + (matches == 1 ? " match" : " matches"));
        if (first >= 0) log.setCaretPosition(first);
    }
}
