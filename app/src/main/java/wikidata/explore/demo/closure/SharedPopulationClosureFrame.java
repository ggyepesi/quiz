package wikidata.explore.demo.closure;

import batch.BatchPolicy;
import batch.BatchProgress;
import batch.InMemoryBatchCheckpointStore;
import wikidata.WikidataBatchFailureClassifier;
import wikidata.WikidataSparqlClient;
import work.CancellationToken;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Simple Swing UI for the shared-population closure demonstration. */
public final class SharedPopulationClosureFrame extends JFrame {
    private static final String USER_AGENT =
            "QuizProject/1.0 (https://github.com/ggyepesi/quiz)";

    private final JTextField startValue = new JTextField(12);
    private final JTextField populationProperty = new JTextField(8);
    private final JTextField nextWaveProperty = new JTextField(8);
    private final JTextField targetRoots = new JTextField(16);
    private final JSpinner maxDepth = new JSpinner(new SpinnerNumberModel(2, 0, 20, 1));
    private final JSpinner batchSize = new JSpinner(new SpinnerNumberModel(8, 1, 500, 1));
    private final JSpinner maxValues = new JSpinner(new SpinnerNumberModel(100, 1, 100_000, 10));
    private final JSpinner maxMemberships = new JSpinner(
            new SpinnerNumberModel(2_000, 1, 1_000_000, 100));
    private final JSpinner maxConnections = new JSpinner(
            new SpinnerNumberModel(5_000, 1, 1_000_000, 100));
    private final JButton run = new JButton("Run");
    private final JButton cancel = new JButton("Cancel");

    private final JTextArea progress = new JTextArea();
    private final TableModel valuesModel = new TableModel("Depth", "QID", "Label");
    private final TableModel membersModel = new TableModel(
            "Wave", "Population QID", "Population", "Member QID", "Member");
    private final TableModel edgesModel = new TableModel(
            "Wave", "From QID", "From", "Via member QID", "Via member",
            "To QID", "To");
    private final TableModel journalModel = new TableModel(
            "Event", "Run", "Target", "Work");
    private final JTabbedPane tabs = new JTabbedPane();

    private volatile CancellationToken activeCancellation;
    private volatile WikidataSparqlClient activeClient;
    private volatile SwingWorker<SharedPopulationClosure.Result, Void> worker;

    public SharedPopulationClosureFrame() {
        super("Shared-population closure");
        applyDefaults();
        buildUi();
        wireActions();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(850, 560));
        setSize(1100, 720);
        setLocationByPlatform(true);
    }

    private void applyDefaults() {
        SharedPopulationClosureConfig defaults =
                SharedPopulationClosureConfig.apostolicKingsOfHungary();
        startValue.setText(defaults.startValueQid());
        populationProperty.setText(defaults.populationPropertyPid());
        nextWaveProperty.setText(defaults.nextWavePropertyPid());
        targetRoots.setText(String.join(",", defaults.targetRootQids()));
        maxDepth.setValue(defaults.maxDepth());
        batchSize.setValue(defaults.batchSize());
        maxValues.setValue(defaults.maxValues());
        maxMemberships.setValue(defaults.maxMembershipsPerUnit());
        maxConnections.setValue(defaults.maxConnectionsPerUnit());
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));
        add(configurationPanel(), BorderLayout.NORTH);

        progress.setEditable(false);
        progress.setLineWrap(false);
        progress.setFont(UIManager.getFont("TextArea.font"));
        tabs.addTab("Progress", new JScrollPane(progress));
        tabs.addTab("Values", table(valuesModel));
        tabs.addTab("Population", table(membersModel));
        tabs.addTab("Connections", table(edgesModel));
        tabs.addTab("Journal", table(journalModel));
        tabs.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel configurationPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;

        addField(fields, c, 0, "Start value", startValue);
        addField(fields, c, 1, "Population property", populationProperty);
        addField(fields, c, 2, "Next-wave property", nextWaveProperty);
        addField(fields, c, 3, "Allowed target roots", targetRoots);
        addField(fields, c, 4, "Max depth", maxDepth);
        addField(fields, c, 5, "Batch size", batchSize);
        addField(fields, c, 6, "Max values", maxValues);
        addField(fields, c, 7, "Memberships/unit", maxMemberships);
        addField(fields, c, 8, "Connections/unit", maxConnections);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        cancel.setEnabled(false);
        actions.add(run);
        actions.add(cancel);
        outer.add(fields, BorderLayout.CENTER);
        outer.add(actions, BorderLayout.SOUTH);
        return outer;
    }

    private static void addField(
            JPanel panel,
            GridBagConstraints c,
            int column,
            String label,
            java.awt.Component field) {
        c.gridx = column;
        c.gridy = 0;
        panel.add(new JLabel(label), c);
        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = field instanceof JTextField ? 1.0 : 0.0;
        panel.add(field, c);
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
    }

    private static JScrollPane table(TableModel model) {
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        return new JScrollPane(table);
    }

    private void wireActions() {
        run.addActionListener(event -> startRun());
        cancel.addActionListener(event -> cancelRun());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                cancelRun();
            }
        });
    }

    private void startRun() {
        SharedPopulationClosureConfig config;
        try {
            config = new SharedPopulationClosureConfig(
                    startValue.getText().trim(),
                    populationProperty.getText().trim(),
                    nextWaveProperty.getText().trim(),
                    (Integer) maxDepth.getValue(),
                    (Integer) batchSize.getValue(),
                    (Integer) maxValues.getValue(),
                    (Integer) maxMemberships.getValue(),
                    (Integer) maxConnections.getValue(),
                    parseQids(targetRoots.getText()));
        } catch (RuntimeException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(),
                    "Invalid configuration", JOptionPane.ERROR_MESSAGE);
            return;
        }

        clearOutput();
        setRunning(true);
        tabs.setSelectedIndex(0);
        append("Starting shared-population closure\n");
        append("  " + config.startValueQid()
                + " <- " + config.populationPropertyPid()
                + " - member - " + config.nextWavePropertyPid() + " -> next value\n");
        append("  allowed target roots: " + config.targetRootQids() + "\n");

        CancellationToken cancellation = new CancellationToken();
        InMemoryBatchCheckpointStore journal = new InMemoryBatchCheckpointStore();
        activeCancellation = cancellation;
        worker = new SwingWorker<>() {
            @Override protected SharedPopulationClosure.Result doInBackground()
                    throws Exception {
                try (WikidataSparqlClient client =
                             new WikidataSparqlClient(
                                     USER_AGENT,
                                     1,
                                     WikidataSparqlClient.WIKIDATA_ENDPOINT,
                                     HttpClient.Version.HTTP_1_1)) {
                    activeClient = client;
                    client.minRequestSpacingMillis(250);
                    client.log(SharedPopulationClosureFrame.this::append);
                    BatchProgress progress = uiProgress();
                    SharedPopulationClosure closure = new SharedPopulationClosure(
                            config,
                            new SparqlSharedPopulationExpansionSource(
                                    client, BatchPolicy.defaults(), progress,
                                    WikidataBatchFailureClassifier.INSTANCE,
                                    cancellation, 1),
                            BatchPolicy.defaults(),
                            progress,
                            WikidataBatchFailureClassifier.INSTANCE,
                            cancellation,
                            journal,
                            1);
                    return closure.execute();
                } finally {
                    activeClient = null;
                }
            }

            @Override protected void done() {
                try {
                    if (isCancelled()) {
                        append("Cancelled.\n");
                    } else {
                        SharedPopulationClosure.Result result = get();
                        showResult(result);
                        append("Finished: " + result.values().size() + " value(s), "
                                + result.members().size() + " population membership(s), "
                                + result.edges().size() + " connection(s).\n");
                        tabs.setSelectedIndex(1);
                    }
                } catch (CancellationException cancelled) {
                    append("Cancelled.\n");
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null
                            ? failure : failure.getCause();
                    append("FAILED: " + message(cause) + "\n");
                    JOptionPane.showMessageDialog(SharedPopulationClosureFrame.this,
                            message(cause), "Traversal failed", JOptionPane.ERROR_MESSAGE);
                } finally {
                    showJournal(journal.events());
                    activeCancellation = null;
                    worker = null;
                    setRunning(false);
                }
            }
        };
        worker.execute();
    }

    private void cancelRun() {
        CancellationToken cancellation = activeCancellation;
        if (cancellation != null) cancellation.cancel();
        WikidataSparqlClient client = activeClient;
        if (client != null) client.cancelCurrentQuery();
        SwingWorker<?, ?> current = worker;
        if (current != null) current.cancel(true);
    }

    private BatchProgress uiProgress() {
        return new BatchProgress() {
            @Override public Running started(String title, String request) {
                append("MAP START: " + title + "\n");
                return new Running() {
                    @Override public void detail(String text) {
                        append("  " + text + "\n");
                    }
                    @Override public void done(String summary) {
                        append("MAP COMPLETE: " + title + " — " + summary + "\n");
                    }
                    @Override public void failed(String error) {
                        append("MAP FAILED: " + title + " — " + error + "\n");
                    }
                };
            }

            @Override public void message(String text) {
                append(text);
            }
        };
    }

    private void showResult(SharedPopulationClosure.Result result) {
        for (SharedPopulationClosure.Value value : result.values()) {
            valuesModel.addRow(new Object[] {
                    value.minimumDepth(), value.qid(), value.label()
            });
        }
        for (SharedPopulationClosure.Wave wave : result.waves()) {
            append("Wave " + wave.depth() + ": rejected "
                    + wave.rejectedTargetCount()
                    + " target(s) outside the allowed hierarchies.\n");
            for (SharedPopulationMember member : wave.members()) {
                membersModel.addRow(new Object[] {
                        wave.depth(), member.sourceQid(), member.sourceLabel(),
                        member.memberQid(), member.memberLabel()
                });
            }
            for (SharedPopulationEdge edge : wave.edges()) {
                edgesModel.addRow(new Object[] {
                        wave.depth(), edge.sourceQid(), edge.sourceLabel(),
                        edge.memberQid(), edge.memberLabel(),
                        edge.targetQid(), edge.targetLabel()
                });
            }
        }
        if (result.valueLimitReached()) {
            append("Stopped because the configured value limit was reached.\n");
        }
        if (result.resourceLimitReached()) {
            append("Stopped incomplete because an acquisition limit was reached.\n");
        }
    }

    private void showJournal(List<InMemoryBatchCheckpointStore.Event> events) {
        journalModel.setRowCount(0);
        for (InMemoryBatchCheckpointStore.Event event : events) {
            journalModel.addRow(new Object[] {
                    event.type(), event.runKey(), text(event.workKey()),
                    event.work().stream()
                            .map(work -> work.descriptor().key())
                            .toList()
            });
        }
    }

    private void clearOutput() {
        progress.setText("");
        valuesModel.setRowCount(0);
        membersModel.setRowCount(0);
        edgesModel.setRowCount(0);
        journalModel.setRowCount(0);
    }

    private void setRunning(boolean running) {
        run.setEnabled(!running);
        cancel.setEnabled(running);
        startValue.setEnabled(!running);
        populationProperty.setEnabled(!running);
        nextWaveProperty.setEnabled(!running);
        targetRoots.setEnabled(!running);
        maxDepth.setEnabled(!running);
        batchSize.setEnabled(!running);
        maxValues.setEnabled(!running);
        maxMemberships.setEnabled(!running);
        maxConnections.setEnabled(!running);
    }

    private void append(String text) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> append(text));
            return;
        }
        progress.append(text);
        progress.setCaretPosition(progress.getDocument().getLength());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static List<String> parseQids(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.trim().split("[\\s,;]+"));
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // The platform-independent look and feel remains usable.
            }
            new SharedPopulationClosureFrame().setVisible(true);
        });
    }

    private static final class TableModel extends DefaultTableModel {
        private TableModel(String... columns) {
            super(columns, 0);
        }

        @Override public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
