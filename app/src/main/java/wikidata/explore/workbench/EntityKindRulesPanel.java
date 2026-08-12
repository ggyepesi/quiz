package wikidata.explore.workbench;

import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.WikidataIds;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Compact ModelBuilder editor for evidence-QID to modeled-kind mappings. */
final class EntityKindRulesPanel extends JPanel {
    private final GeneratedProjectModel project;
    private final List<EntityKindRule> rows = new ArrayList<>();
    private final RulesModel tableModel = new RulesModel();
    private final JTable table = new JTable(tableModel);

    EntityKindRulesPanel(GeneratedProjectModel project, Runnable changed) {
        this.project = project;
        project.entityKindRules().forEach(rule -> rows.add(rule.copy()));
        setLayout(new BorderLayout(6, 6));
        add(new JLabel("Map entity evidence to modeled kinds (usually P31). "
                + "One entity may match several rules."), BorderLayout.NORTH);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JComboBox<String> classes = new JComboBox<>(project.classes().stream()
                .map(value -> value.className()).toArray(String[]::new));
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(classes));
        add(new JScrollPane(table), BorderLayout.CENTER);

        JButton add = new JButton("Add rule");
        add.addActionListener(e -> {
            String initial = project.classes().isEmpty() ? ""
                    : project.classes().getFirst().className();
            rows.add(new EntityKindRule(initial, List.of()));
            tableModel.fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        });
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                rows.remove(table.convertRowIndexToModel(row));
                tableModel.fireTableDataChanged();
            }
        });
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            List<EntityKindRule> invalid = rows.stream()
                    .filter(rule -> !rule.isConfigured()
                            || project.findClass(rule.className()) == null).toList();
            if (!invalid.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Every rule needs an existing class, a PID, and at least one QID.",
                        "Incomplete kind rule", JOptionPane.WARNING_MESSAGE);
                return;
            }
            project.entityKindRules(rows);
            if (changed != null) changed.run();
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(add);
        actions.add(remove);
        actions.add(apply);
        add(actions, BorderLayout.SOUTH);
    }

    private final class RulesModel extends AbstractTableModel {
        private final String[] columns = {"Modeled kind", "Evidence PID", "Evidence QIDs"};
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public boolean isCellEditable(int row, int column) { return true; }
        @Override public Object getValueAt(int row, int column) {
            EntityKindRule rule = rows.get(row);
            return switch (column) {
                case 0 -> rule.className();
                case 1 -> rule.propertyPid();
                default -> String.join(", ", rule.evidenceQids());
            };
        }
        @Override public void setValueAt(Object value, int row, int column) {
            EntityKindRule rule = rows.get(row);
            String text = value == null ? "" : value.toString().trim();
            switch (column) {
                case 0 -> rule.className(text);
                case 1 -> rule.propertyPid(text);
                default -> {
                    List<String> tokens = Arrays.stream(text.split("[,\\s]+"))
                            .filter(token -> !token.isBlank()).toList();
                    List<String> invalid = tokens.stream()
                            .filter(token -> !WikidataIds.isQid(token)).toList();
                    if (!invalid.isEmpty()) {
                        Toolkit.getDefaultToolkit().beep();
                        JOptionPane.showMessageDialog(EntityKindRulesPanel.this,
                                "Evidence values must be Wikidata QIDs. Invalid: "
                                        + String.join(", ", invalid),
                                "Invalid evidence QID", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    rule.evidenceQids(tokens);
                }
            }
            fireTableCellUpdated(row, column);
        }
    }
}
