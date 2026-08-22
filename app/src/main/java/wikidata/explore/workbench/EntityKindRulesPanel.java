package wikidata.explore.workbench;

import wikidata.explore.model.EntityKindCoverage;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.WikidataIds;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import wikidata.ui.WikidataLinks;

/**
 * Compact ModelBuilder editor for evidence-QID to modeled-kind mappings.
 *
 * <p>Evidence can be picked from a vocabulary rather than typed: a descriptive vocabulary
 * is the observed set of values the pool currently carries, so choosing from it cannot produce a
 * rule that matches nothing, and what is left unclaimed is visible before a regeneration
 * is spent rather than reported as "M of unknown kind" afterwards. Explore stays available
 * for a value the data has not shown yet.</p>
 */
final class EntityKindRulesPanel extends JPanel {
    private final GeneratedProjectModel project;
    private final List<EntityKindRule> rows = new ArrayList<>();
    private final RulesModel tableModel = new RulesModel();
    private final JTable table = new JTable(tableModel);
    private final Function<String, String> labels;
    private final BiConsumer<String, Consumer<String>> explore;

    EntityKindRulesPanel(GeneratedProjectModel project, Runnable changed) {
        this(project, changed, qid -> qid, null);
    }

    /**
     * @param labels  qid → display label, from the loaded pool
     * @param explore opens the Wikidata entity picker with a seed term, feeding back a qid;
     *                null when no query runner is available
     */
    EntityKindRulesPanel(GeneratedProjectModel project, Runnable changed,
                         Function<String, String> labels,
                         BiConsumer<String, Consumer<String>> explore) {
        this.project = project;
        this.labels = labels == null ? qid -> qid : labels;
        this.explore = explore;
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
        JButton fromVocabulary = new JButton("From vocabulary…");
        fromVocabulary.setToolTipText(
                "Pick evidence from the values the pool actually carries");
        fromVocabulary.addActionListener(e -> pickFromVocabulary());
        JButton findQid = new JButton("Find QID…");
        findQid.setToolTipText("Search Wikidata for a value the data has not shown yet");
        findQid.setEnabled(explore != null);
        findQid.addActionListener(e -> findQid());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(add);
        actions.add(remove);
        actions.add(fromVocabulary);
        actions.add(findQid);
        actions.add(apply);
        add(actions, BorderLayout.SOUTH);
    }

    /** The rule being edited, or null with a nudge when nothing is selected. */
    private EntityKindRule selectedRule() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this,
                    "Select the rule the evidence belongs to first.",
                    "No rule selected", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return rows.get(table.convertRowIndexToModel(row));
    }

    private void addEvidence(EntityKindRule rule, List<String> qids) {
        List<String> merged = new ArrayList<>(rule.evidenceQids());
        qids.stream().filter(qid -> !merged.contains(qid)).forEach(merged::add);
        rule.evidenceQids(merged);
        tableModel.fireTableDataChanged();
    }

    private void findQid() {
        EntityKindRule rule = selectedRule();
        if (rule == null || explore == null) {
            return;
        }
        explore.accept("", qid -> {
            if (WikidataIds.isQid(qid)) {
                addEvidence(rule, List.of(qid));
            }
        });
    }

    /** Choose evidence from a vocabulary, showing what each value is already claimed by
     *  and — the point of the view — what nothing claims yet. */
    private void pickFromVocabulary() {
        EntityKindRule rule = selectedRule();
        if (rule == null) {
            return;
        }
        List<String> vocabularies = EntityKindCoverage.vocabularies(
                project, rule.propertyPid());
        if (vocabularies.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No populated vocabulary is produced from " + rule.propertyPid()
                            + ". Open a saved/generated domain, or configure a field "
                            + "that loads this property.",
                    "No matching evidence vocabulary", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<String> which = new JComboBox<>(vocabularies.toArray(new String[0]));
        JCheckBox onlyUnmapped = new JCheckBox("Only values no kind claims yet");
        MembersModel members = new MembersModel();
        JTable memberTable = new JTable(members);
        memberTable.getColumnModel().getColumn(0).setMaxWidth(28);
        // A QID is a link wherever it is shown. Same rule as the cards and the model
        // graph — WikidataLinks decides the URL, the view only offers the affordance.
        memberTable.getColumnModel().getColumn(1).setCellRenderer(new LinkCellRenderer());
        memberTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = memberTable.rowAtPoint(event.getPoint());
                if (row < 0 || memberTable.columnAtPoint(event.getPoint()) != 1) {
                    return;
                }
                WikidataLinks.open(String.valueOf(members.getValueAt(
                        memberTable.convertRowIndexToModel(row), 1)));
            }
        });
        memberTable.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent event) {
                memberTable.setCursor(Cursor.getPredefinedCursor(
                        memberTable.columnAtPoint(event.getPoint()) == 1
                                ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
        JLabel summary = new JLabel();
        Runnable reload = () -> {
            List<EntityKindCoverage.Member> all = EntityKindCoverage.members(
                    project, (String) which.getSelectedItem(), rule.propertyPid(),
                    rows, labels);
            summary.setText(all.size() + " value(s), " + EntityKindCoverage.unmapped(all)
                    + " unclaimed");
            members.show(all, onlyUnmapped.isSelected(), rule);
        };
        which.addActionListener(e -> reload.run());
        onlyUnmapped.addActionListener(e -> reload.run());
        reload.run();

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Vocabulary:"));
        top.add(which);
        top.add(onlyUnmapped);
        top.add(summary);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.add(top, BorderLayout.NORTH);
        content.add(new JScrollPane(memberTable), BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(620, 380));

        int choice = JOptionPane.showConfirmDialog(this, content,
                "Evidence for \"" + rule.className() + "\"",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            members.applyTo(rule);
            tableModel.fireTableDataChanged();
        }
    }

    /** Paints a QID the way every other view paints one: as a link. */
    private static final class LinkCellRenderer
            extends javax.swing.table.DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focus,
                int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            setForeground(selected ? table.getSelectionForeground() : new Color(0, 102, 204));
            setText("<html><u>" + (value == null ? "" : value) + "</u></html>");
            return this;
        }
    }

    /** Checkbox list over one vocabulary's values, with their current claims. */
    private static final class MembersModel extends AbstractTableModel {
        private final String[] columns = {"", "Value", "Label", "Claimed by"};
        private final List<EntityKindCoverage.Member> shown = new ArrayList<>();
        private final Set<String> checked = new LinkedHashSet<>();
        private final Set<String> presented = new LinkedHashSet<>();
        private boolean initialized;

        void show(List<EntityKindCoverage.Member> all, boolean unmappedOnly,
                  EntityKindRule rule) {
            if (!initialized) {
                checked.addAll(rule.evidenceQids());
                initialized = true;
            }
            all.forEach(member -> presented.add(member.qid()));
            shown.clear();
            all.stream().filter(member -> !unmappedOnly || !member.mapped())
                    .forEach(shown::add);
            fireTableDataChanged();
        }

        void applyTo(EntityKindRule rule) {
            List<String> reconciled = new ArrayList<>();
            rule.evidenceQids().stream().filter(qid -> !presented.contains(qid))
                    .forEach(reconciled::add);
            checked.stream().filter(presented::contains)
                    .filter(qid -> !reconciled.contains(qid)).forEach(reconciled::add);
            rule.evidenceQids(reconciled);
        }

        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public Object getValueAt(int row, int column) {
            EntityKindCoverage.Member member = shown.get(row);
            return switch (column) {
                case 0 -> checked.contains(member.qid());
                case 1 -> member.qid();
                case 2 -> member.label();
                default -> member.mapped() ? String.join(", ", member.kinds()) : "—";
            };
        }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column != 0) {
                return;
            }
            String qid = shown.get(row).qid();
            if (Boolean.TRUE.equals(value)) {
                checked.add(qid);
            } else {
                checked.remove(qid);
            }
            fireTableCellUpdated(row, column);
        }
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
