package wikidata.explore.workbench;

import wikidata.WikidataIds;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.query.logical.DiscoverSubtypesQuery;
import wikidata.explore.query.result.TableQueryResult;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.rule.RuleNode;
import wikidata.ui.WikidataLinks;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;

/** Explorer tool for inspecting direct Wikidata subclasses and their populations. */
final class SubclassDiscoveryPanel extends JPanel {

    private final JTextField rootQid = new JTextField(10);
    private final JButton run = new JButton("Find direct subclasses");
    private final JButton cancel = new JButton("Cancel");
    private final JButton add = new JButton("Add selected to membership");
    private final JLabel status = new JLabel(" ");
    private final DefaultTableModel rows = new DefaultTableModel(
            new Object[] {"Subclass", "Instances added", "Examples", "QID"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(rows);
    private Consumer<String> onAddMembershipTarget = ignored -> { };
    private String selectedClassName = "";
    private boolean addable;

    private static final String ADD_NEEDS_A_RELATION =
            "has no membership property and object yet — configure the class's "
                    + "triple before adding targets to it.";

    SubclassDiscoveryPanel() {
        super(new BorderLayout(4, 4));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        controls.add(new JLabel("Superclass QID:"));
        controls.add(rootQid);
        controls.add(run);
        controls.add(cancel);
        controls.add(status);

        JLabel explanation = new JLabel("<html>Finds direct P279 subclasses of the "
                + "QID and estimates how many additional instances each would admit. "
                + "Inspecting does not change the class; adding a selected row does.</html>");
        JPanel north = new JPanel(new BorderLayout(4, 2));
        north.add(explanation, BorderLayout.NORTH);
        north.add(controls, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(380);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        WikidataLinks.installOnColumn(table, 3);
        add(new JScrollPane(table), BorderLayout.CENTER);

        add.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(event ->
                add.setEnabled(addable && table.getSelectedRowCount() > 0));
        add.addActionListener(event -> addSelected());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        actions.add(add);
        add(actions, BorderLayout.SOUTH);
        cancel.setEnabled(false);
    }

    void setQueryRunner(SwingQueryRunner runner) {
        if (runner == null) return;
        runner.registerCancelButton(cancel);
        runner.wireButton(run, this::accept, this::query,
                failure -> status.setText("Error: " + failure.getMessage()));
    }

    void onAddMembershipTarget(Consumer<String> handler) {
        onAddMembershipTarget = handler == null ? ignored -> { } : handler;
    }

    void showClass(GeneratedClassModel clazz) {
        selectedClassName = clazz == null ? "" : clazz.className();
        // A target is added to a membership relation. A class that has none yet has
        // nothing to add one to, and Add would do nothing at all — so it says why
        // here instead of accepting the click and answering with silence.
        addable = clazz != null
                && clazz.membership().kind() == EntityBound.Kind.RELATION;
        String qid = clazz == null || clazz.membership().qids().isEmpty()
                ? "" : clazz.membership().qids().getFirst();
        rootQid.setText(qid);
        add.setEnabled(false);
        add.setToolTipText(addable ? null : ADD_NEEDS_A_RELATION);
        status.setText(selectedClassName.isBlank()
                ? "Select a Source class to add membership targets."
                : addable
                        ? "Results can be added to " + selectedClassName + "."
                        : selectedClassName + " " + ADD_NEEDS_A_RELATION);
    }

    private DiscoverSubtypesQuery query() {
        String qid = RuleNode.cleanQid(rootQid.getText());
        if (!WikidataIds.isQid(qid)) {
            status.setText("Enter a Wikidata QID.");
            return null;
        }
        status.setText("Finding subclasses of " + qid + "…");
        return new DiscoverSubtypesQuery(qid, 200);
    }

    void accept(TableQueryResult result) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            rows.setRowCount(0);
            List<List<Object>> values = result == null ? List.of() : result.rows();
            for (List<Object> value : values) {
                rows.addRow(new Object[] {
                        at(value, 0), at(value, 1), at(value, 2), at(value, 3)});
            }
            status.setText(values.isEmpty() ? "No direct subclasses found."
                    : values.size() + " direct subclasses found.");
            add.setEnabled(false);
            add.setToolTipText(addable ? null : ADD_NEEDS_A_RELATION);
        });
    }

    private void addSelected() {
        for (int selected : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(selected);
            String qid = RuleNode.cleanQid(String.valueOf(rows.getValueAt(modelRow, 3)));
            if (WikidataIds.isQid(qid)) onAddMembershipTarget.accept(qid);
        }
    }

    private static Object at(List<Object> row, int index) {
        return row != null && index < row.size() ? row.get(index) : "";
    }

    // Behavioral/UI-test accessors.
    int resultCount() { return rows.getRowCount(); }
    boolean addEnabled() { return add.isEnabled(); }
    String addRefusal() { return add.getToolTipText(); }
    String statusText() { return status.getText(); }
    void selectRows(int... selected) {
        table.clearSelection();
        for (int row : selected) table.addRowSelectionInterval(row, row);
    }
    void addSelectedRows() { addSelected(); }
}
