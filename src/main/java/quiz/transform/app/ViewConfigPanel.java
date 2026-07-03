package quiz.transform.app;

import quiz.transform.ViewSpec;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Edits a {@link ViewSpec} — the type to keep, field==value filters, and ordered
 * grouping — with field dropdowns sourced from the loaded snapshot's
 * {@link DomainSchema}. Builds the spec on demand; the app renders it.
 */
public class ViewConfigPanel extends JPanel {

    private final DomainSchema schema;

    private final JTextField nameField = new JTextField(18);
    private final JComboBox<String> typeBox = new JComboBox<>();

    private final List<String[]> filters = new ArrayList<>();   // {field, value}
    private final List<String[]> groups = new ArrayList<>();     // {field, mode}
    private final FilterModel filterModel = new FilterModel();
    private final GroupModel groupModel = new GroupModel();
    private final JTable filterTable = new JTable(filterModel);
    private final JTable groupTable = new JTable(groupModel);

    private Runnable onRender = () -> {};

    public ViewConfigPanel(DomainSchema schema, ViewSpec initial) {
        this.schema = schema;
        setLayout(new BorderLayout(6, 6));

        for (String t : schema.types()) {
            typeBox.addItem(t);
        }
        typeBox.addActionListener(e -> refreshFieldEditors());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Name:"));
        top.add(nameField);
        top.add(new JLabel("Type:"));
        top.add(typeBox);
        add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(2, 1, 6, 6));
        center.add(section("Filters (field == value)", filterTable,
                () -> { filters.add(new String[]{firstField(), ""}); filterModel.fireTableDataChanged(); },
                () -> removeSelected(filterTable, filters, filterModel)));
        center.add(groupSection());
        add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton render = new JButton("Render");
        render.addActionListener(e -> onRender.run());
        JButton save = new JButton("Save…");
        save.addActionListener(e -> save());
        JButton load = new JButton("Load…");
        load.addActionListener(e -> load());
        bottom.add(load);
        bottom.add(save);
        bottom.add(render);
        add(bottom, BorderLayout.SOUTH);

        if (initial != null) {
            apply(initial);
        }
        refreshFieldEditors();
    }

    public void setOnRender(Runnable r) {
        this.onRender = r == null ? () -> {} : r;
    }

    public ViewSpec buildSpec() {
        ViewSpec spec = new ViewSpec();
        spec.name = nameField.getText().trim();
        spec.memberType = typeBox.getSelectedItem() == null ? "" : typeBox.getSelectedItem().toString();
        for (String[] f : filters) {
            if (f[0] != null && !f[0].isBlank()) {
                spec.filters.add(new ViewSpec.Filter(f[0], ViewSpecJsonIO.parseValue(f[1])));
            }
        }
        for (String[] g : groups) {
            if (g[0] != null && !g[0].isBlank()) {
                spec.grouping.add(new ViewSpec.GroupBy(g[0], g[1]));
            }
        }
        return spec;
    }

    // --- helpers ---

    private JPanel groupSection() {
        JPanel wrap = new JPanel(new BorderLayout(4, 4));
        wrap.setBorder(BorderFactory.createTitledBorder("Grouping (drill-down, in order)"));
        wrap.add(new JScrollPane(groupTable), BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(button("Add", () -> { groups.add(new String[]{firstField(), "VALUE"}); groupModel.fireTableDataChanged(); }));
        bar.add(button("Remove", () -> removeSelected(groupTable, groups, groupModel)));
        bar.add(button("Up", () -> move(groupTable, groups, groupModel, -1)));
        bar.add(button("Down", () -> move(groupTable, groups, groupModel, 1)));
        wrap.add(bar, BorderLayout.SOUTH);
        return wrap;
    }

    private JPanel section(String title, JTable table, Runnable add, Runnable remove) {
        JPanel wrap = new JPanel(new BorderLayout(4, 4));
        wrap.setBorder(BorderFactory.createTitledBorder(title));
        wrap.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        bar.add(button("Add", add));
        bar.add(button("Remove", remove));
        wrap.add(bar, BorderLayout.SOUTH);
        return wrap;
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private String firstField() {
        List<String> fs = currentFields();
        return fs.isEmpty() ? "" : fs.get(0);
    }

    private List<String> currentFields() {
        Object t = typeBox.getSelectedItem();
        return t == null ? List.of() : schema.fields(t.toString());
    }

    // Field columns use a combo of the selected type's fields.
    private void refreshFieldEditors() {
        JComboBox<String> combo = new JComboBox<>(currentFields().toArray(new String[0]));
        filterTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(combo));
        JComboBox<String> combo2 = new JComboBox<>(currentFields().toArray(new String[0]));
        groupTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(combo2));
        groupTable.getColumnModel().getColumn(1).setCellEditor(
                new DefaultCellEditor(new JComboBox<>(new String[]{"VALUE", "REFERENCE"})));
    }

    private void removeSelected(JTable table, List<String[]> rows, AbstractTableModel model) {
        int r = table.getSelectedRow();
        if (r >= 0 && r < rows.size()) {
            rows.remove(r);
            model.fireTableDataChanged();
        }
    }

    private void move(JTable table, List<String[]> rows, AbstractTableModel model, int delta) {
        int r = table.getSelectedRow();
        int n = r + delta;
        if (r >= 0 && n >= 0 && n < rows.size()) {
            String[] tmp = rows.get(r);
            rows.set(r, rows.get(n));
            rows.set(n, tmp);
            model.fireTableDataChanged();
            table.getSelectionModel().setSelectionInterval(n, n);
        }
    }

    private void apply(ViewSpec spec) {
        nameField.setText(spec.name == null ? "" : spec.name);
        if (spec.memberType != null && !spec.memberType.isBlank()) {
            typeBox.setSelectedItem(spec.memberType);
        }
        filters.clear();
        for (ViewSpec.Filter f : spec.filters) {
            filters.add(new String[]{f.field, f.value == null ? "" : String.valueOf(f.value)});
        }
        groups.clear();
        for (ViewSpec.GroupBy g : spec.grouping) {
            groups.add(new String[]{g.field, g.mode == null ? "VALUE" : g.mode});
        }
        filterModel.fireTableDataChanged();
        groupModel.fireTableDataChanged();
    }

    private void save() {
        JFileChooser fc = new JFileChooser(new File("data"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            ViewSpecJsonIO.save(fc.getSelectedFile(), buildSpec());
        }
    }

    private void load() {
        JFileChooser fc = new JFileChooser(new File("data"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            apply(ViewSpecJsonIO.load(fc.getSelectedFile()));
            refreshFieldEditors();
            onRender.run();
        }
    }

    private class FilterModel extends AbstractTableModel {
        private final String[] cols = {"Field", "Value"};
        @Override public int getRowCount() { return filters.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public boolean isCellEditable(int r, int c) { return true; }
        @Override public Object getValueAt(int r, int c) { return filters.get(r)[c]; }
        @Override public void setValueAt(Object v, int r, int c) {
            filters.get(r)[c] = v == null ? "" : v.toString();
        }
    }

    private class GroupModel extends AbstractTableModel {
        private final String[] cols = {"Field", "Mode"};
        @Override public int getRowCount() { return groups.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public boolean isCellEditable(int r, int c) { return true; }
        @Override public Object getValueAt(int r, int c) { return groups.get(r)[c]; }
        @Override public void setValueAt(Object v, int r, int c) {
            groups.get(r)[c] = v == null ? "" : v.toString();
        }
    }
}
