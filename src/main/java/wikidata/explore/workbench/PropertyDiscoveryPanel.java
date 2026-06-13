package wikidata.explore.workbench;

import wikidata.explore.query.logical.DiscoverClassPropertiesQuery;
import wikidata.explore.query.logical.EntityLabelQuery;
import wikidata.explore.query.result.DiscoveredProperty;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.rule.RuleNode;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Unified class-level property discovery panel (replaces NodePropertyDiscoveryPanel + WikidataItemAttributePanel).
 *
 * Always queries at class level by sampling N instances of a class, then profiling
 * which properties they have (outgoing) or which properties reference them (incoming).
 *
 * The class to sample comes from the current tree node (via nodeSupplier) by default.
 * The user may override with a custom class QID.
 */
public class PropertyDiscoveryPanel extends JPanel {

    private static final int DEFAULT_SAMPLE = 10;

    // --- External wiring ---
    private SwingQueryRunner queryRunner;
    private Consumer<String> log = s -> {};
    private Supplier<RuleNode> nodeSupplier = () -> null;
    // Read-only title source. Must NOT be the (edit-applying) nodeSupplier:
    // refreshNodeTitle() is a display update and may run during a tree
    // selection, so mutating the model here re-fires selection → edit →
    // refresh and recurses until the stack overflows.
    private Supplier<String> nodeTitleSupplier = () -> null;
    private Runnable applyEdits = () -> {};
    private Consumer<DiscoveredProperty> onAddField = p -> {};
    private Consumer<String> onAddAllowedQid  = qid -> {};
    private Consumer<String> onAddExcludedQid = qid -> {};

    // --- UI ---
    private final JLabel     nodeLabel       = new JLabel("No node selected");
    private final JTextField customQidField  = new JTextField(8);
    private final JLabel     customQidLabel  = new JLabel(" ");
    private final JSpinner   sampleSpinner   = new JSpinner(new SpinnerNumberModel(DEFAULT_SAMPLE, 1, 50, 5));
    private final JButton    runButton       = new JButton("Run");
    private final JButton    cancelButton    = new JButton("Cancel");
    private final JLabel     statusLabel     = new JLabel(" ");
    private final JTextField searchField     = new JTextField(12);

    // --- Table ---
    private final List<DiscoveredProperty>     properties = new ArrayList<>();
    private final PropTableModel               tableModel = new PropTableModel(properties);
    private final TableRowSorter<PropTableModel> sorter   = new TableRowSorter<>(tableModel);
    private final JTable                       table      = new JTable(tableModel);

    private Timer qidResolveTimer;

    public PropertyDiscoveryPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    // --- Wiring setters ---

    /**
     * One-shot: the run button's action listener binds to the first
     * runner's workflow, so later calls are ignored.
     */
    public void setQueryRunner(SwingQueryRunner queryRunner) {
        if (this.queryRunner != null || queryRunner == null) {
            return;
        }

        this.queryRunner = queryRunner;

        queryRunner.registerCancelButton(cancelButton);

        queryRunner.wireButton(
                runButton,
                this::acceptDiscovery,
                this::buildDiscoveryQuery,
                ex -> statusLabel.setText("Error: " + ex.getMessage()));
    }

    public void log(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public void setNodeSupplier(Supplier<RuleNode> supplier) {
        this.nodeSupplier = supplier == null ? () -> null : supplier;
    }

    public void setApplyEdits(Runnable applyEdits) {
        this.applyEdits = applyEdits == null ? () -> {} : applyEdits;
    }

    public void setNodeTitleSupplier(Supplier<String> supplier) {
        this.nodeTitleSupplier = supplier == null ? () -> null : supplier;
    }

    public void refreshNodeTitle() {
        String title = nodeTitleSupplier.get();
        nodeLabel.setText(title == null || title.isBlank()
                ? "No node selected"
                : title);
    }

    public void onAddField(Consumer<DiscoveredProperty> handler) {
        this.onAddField = handler == null ? p -> {} : handler;
    }

    public void onAddAllowedQid(Consumer<String> handler) {
        this.onAddAllowedQid = handler == null ? qid -> {} : handler;
    }

    public void onAddExcludedQid(Consumer<String> handler) {
        this.onAddExcludedQid = handler == null ? qid -> {} : handler;
    }

    // --- UI build ---

    private void buildUi() {
        table.setFillsViewportHeight(true);
        table.setRowHeight(24);
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        table.getColumnModel().getColumn(PropTableModel.COL_PID)       .setPreferredWidth(55);
        table.getColumnModel().getColumn(PropTableModel.COL_LABEL)     .setPreferredWidth(160);
        table.getColumnModel().getColumn(PropTableModel.COL_DIR)       .setPreferredWidth(65);
        table.getColumnModel().getColumn(PropTableModel.COL_TYPE)      .setPreferredWidth(75);
        table.getColumnModel().getColumn(PropTableModel.COL_COUNT)     .setPreferredWidth(65);
        table.getColumnModel().getColumn(PropTableModel.COL_EXAMPLE)   .setPreferredWidth(160);
        table.getColumnModel().getColumn(PropTableModel.COL_ADD_FIELD) .setPreferredWidth(80);
        table.getColumnModel().getColumn(PropTableModel.COL_ALLOW)     .setPreferredWidth(55);
        table.getColumnModel().getColumn(PropTableModel.COL_EXCLUDE)   .setPreferredWidth(60);

        ButtonRenderer btnRenderer = new ButtonRenderer();
        for (int c : new int[]{PropTableModel.COL_ADD_FIELD, PropTableModel.COL_ALLOW, PropTableModel.COL_EXCLUDE}) {
            table.getColumnModel().getColumn(c).setCellRenderer(btnRenderer);
        }
        table.getColumnModel().getColumn(PropTableModel.COL_ADD_FIELD)
                .setCellEditor(new ButtonEditor("Add Field", viewRow -> {
                    int r = table.convertRowIndexToModel(viewRow);
                    if (r >= 0 && r < properties.size()) onAddField.accept(properties.get(r));
                }));
        table.getColumnModel().getColumn(PropTableModel.COL_ALLOW)
                .setCellEditor(new ButtonEditor("Allow", viewRow -> {
                    int r = table.convertRowIndexToModel(viewRow);
                    if (r >= 0 && r < properties.size()) {
                        String qid = properties.get(r).exampleQid();
                        if (qid != null && !qid.isBlank()) onAddAllowedQid.accept(qid);
                    }
                }));
        table.getColumnModel().getColumn(PropTableModel.COL_EXCLUDE)
                .setCellEditor(new ButtonEditor("Exclude", viewRow -> {
                    int r = table.convertRowIndexToModel(viewRow);
                    if (r >= 0 && r < properties.size()) {
                        String qid = properties.get(r).exampleQid();
                        if (qid != null && !qid.isBlank()) onAddExcludedQid.accept(qid);
                    }
                }));

        WdLinkRenderer linkRenderer = new WdLinkRenderer();
        table.getColumnModel().getColumn(PropTableModel.COL_PID)    .setCellRenderer(linkRenderer);
        table.getColumnModel().getColumn(PropTableModel.COL_EXAMPLE).setCellRenderer(linkRenderer);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int vr = table.rowAtPoint(e.getPoint());
                int vc = table.columnAtPoint(e.getPoint());
                if (vr < 0) return;
                int mr = table.convertRowIndexToModel(vr);
                int mc = table.convertColumnIndexToModel(vc);
                if (mr < 0 || mr >= properties.size()) return;
                DiscoveredProperty p = properties.get(mr);
                if (mc == PropTableModel.COL_PID)
                    openInBrowser("https://www.wikidata.org/wiki/Property:" + p.pid());
                else if (mc == PropTableModel.COL_EXAMPLE && !p.exampleQid().isBlank())
                    openInBrowser("https://www.wikidata.org/wiki/" + p.exampleQid());
            }
        });
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int vr = table.rowAtPoint(e.getPoint());
                int vc = table.columnAtPoint(e.getPoint());
                int mc = table.convertColumnIndexToModel(vc);
                int mr = vr >= 0 ? table.convertRowIndexToModel(vr) : -1;
                boolean link = false;
                if (mr >= 0 && mr < properties.size()) {
                    if (mc == PropTableModel.COL_PID) link = true;
                    if (mc == PropTableModel.COL_EXAMPLE) link = !properties.get(mr).exampleQid().isBlank();
                }
                table.setCursor(link ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });

        nodeLabel.setFont(nodeLabel.getFont().deriveFont(Font.BOLD));
        customQidField.setToolTipText("Leave empty to use the current tree node; or enter a class QID to override");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Class:"));
        controls.add(nodeLabel);
        controls.add(new JLabel("  Override QID:"));
        controls.add(customQidField);
        controls.add(customQidLabel);
        controls.add(new JLabel("N:"));
        controls.add(sampleSpinner);
        controls.add(runButton);
        controls.add(cancelButton);
        controls.add(new JLabel("  Search:"));
        controls.add(searchField);
        controls.add(statusLabel);

        add(controls, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        cancelButton.setEnabled(false);

        customQidField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { scheduleQidResolve(); }
            public void removeUpdate(DocumentEvent e)  { scheduleQidResolve(); }
            public void changedUpdate(DocumentEvent e) {}
        });
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) {}
        });
    }

    private void applyFilter() {
        String text = searchField.getText();
        sorter.setRowFilter(text == null || text.isBlank()
                ? null
                : RowFilter.regexFilter("(?i)" + Pattern.quote(text.trim())));
    }

    private void scheduleQidResolve() {
        customQidLabel.setText(" ");
        if (qidResolveTimer != null) qidResolveTimer.stop();
        String raw = customQidField.getText().trim();
        if (!raw.matches("Q\\d+")) return;
        qidResolveTimer = new Timer(600, e -> resolveQidLabel(raw));
        qidResolveTimer.setRepeats(false);
        qidResolveTimer.start();
    }

    private void resolveQidLabel(String qid) {
        if (queryRunner == null) return;

        queryRunner.runQuiet(
                new EntityLabelQuery(qid),
                label -> SwingUtilities.invokeLater(() ->
                        customQidLabel.setText(label != null
                                ? qid + " · " + label
                                : qid + " (no label)")),
                ignored -> {});
    }

    // --- Discovery ---

    private DiscoverClassPropertiesQuery buildDiscoveryQuery() {
        applyEdits.run();

        String customQid = customQidField.getText().trim();
        boolean useCustom = customQid.matches("Q\\d+");
        int     n         = (int) sampleSpinner.getValue();

        RuleNode node = nodeSupplier.get();
        if (node == null && !useCustom) {
            statusLabel.setText("Select a tree node or enter a class QID.");
            return null;
        }

        if (!useCustom) {
            String name = node.name() != null ? node.name() : "?";
            String qid  = node.sourceQid();
            nodeLabel.setText(name + (qid != null && !qid.isBlank() ? " (" + qid + ")" : ""));
        }

        properties.clear();
        tableModel.fireTableDataChanged();
        statusLabel.setText("Discovering properties...");

        return new DiscoverClassPropertiesQuery(
                node,
                useCustom ? customQid : "",
                n);
    }

    private void acceptDiscovery(List<DiscoveredProperty> result) {
        List<DiscoveredProperty> rows =
                result == null ? List.of() : result;

        SwingUtilities.invokeLater(() -> {
            properties.clear();
            properties.addAll(rows);
            tableModel.fireTableDataChanged();
            statusLabel.setText(rows.isEmpty()
                    ? "No properties found."
                    : rows.size() + " properties found.");
        });
    }

    // --- Static helpers ---

    private static void openInBrowser(String url) {
        aux.BrowserLauncher.open(url);
    }

    // --- Table model ---

    static final class PropTableModel extends AbstractTableModel {
        static final int COL_PID       = 0;
        static final int COL_LABEL     = 1;
        static final int COL_DIR       = 2;
        static final int COL_TYPE      = 3;
        static final int COL_COUNT     = 4;
        static final int COL_EXAMPLE   = 5;
        static final int COL_ADD_FIELD = 6;
        static final int COL_ALLOW     = 7;
        static final int COL_EXCLUDE   = 8;

        private static final String[] COLUMNS =
                { "PID", "Label", "Dir", "Type", "N / sample", "Example", "Add Field", "Allow", "Exclude" };

        private final List<DiscoveredProperty> rows;

        PropTableModel(List<DiscoveredProperty> rows) { this.rows = rows; }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int col) { return COLUMNS[col]; }

        @Override public Class<?> getColumnClass(int col) {
            return (col == COL_ADD_FIELD || col == COL_ALLOW || col == COL_EXCLUDE)
                    ? JButton.class : String.class;
        }

        @Override public boolean isCellEditable(int r, int c) {
            return c == COL_ADD_FIELD || c == COL_ALLOW || c == COL_EXCLUDE;
        }

        @Override public Object getValueAt(int row, int col) {
            DiscoveredProperty p = rows.get(row);
            return switch (col) {
                case COL_PID       -> p.pid();
                case COL_LABEL     -> p.label();
                case COL_DIR       -> p.direction();
                case COL_TYPE      -> p.typeLabel();
                case COL_COUNT     -> p.frequency();
                case COL_EXAMPLE   -> p.exampleDisplay();
                case COL_ADD_FIELD -> "Add Field";
                case COL_ALLOW     -> p.exampleQid().isBlank() ? "" : "Allow";
                case COL_EXCLUDE   -> p.exampleQid().isBlank() ? "" : "Exclude";
                default -> "";
            };
        }
    }

    // --- Renderers / editors ---

    private static class WdLinkRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(table, value, sel, focus, row, col);
            String text = value == null ? "" : value.toString();
            if (!sel && text.matches("[PQ]\\d+"))
                setForeground(new Color(0, 80, 200));
            else if (!sel)
                setForeground(table.getForeground());
            return this;
        }
    }

    private static class ButtonRenderer extends JButton
            implements javax.swing.table.TableCellRenderer {
        ButtonRenderer() { setOpaque(true); }
        @Override public Component getTableCellRendererComponent(
                JTable table, Object value, boolean sel, boolean focus, int row, int col) {
            String text = value == null ? "" : value.toString();
            setText(text);
            setEnabled(!text.isBlank());
            return this;
        }
    }

    private static class ButtonEditor extends DefaultCellEditor {
        private final JButton button;
        private int currentRow;

        ButtonEditor(String label, java.util.function.IntConsumer action) {
            super(new JCheckBox());
            button = new JButton(label);
            button.addActionListener(e -> { fireEditingStopped(); if (button.isEnabled()) action.accept(currentRow); });
        }
        @Override public Component getTableCellEditorComponent(
                JTable table, Object value, boolean sel, int row, int col) {
            currentRow = row;
            String text = value == null ? "" : value.toString();
            button.setText(text);
            button.setEnabled(!text.isBlank());
            return button;
        }
        @Override public Object getCellEditorValue() { return button.getText(); }
    }
}
