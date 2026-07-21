package wikidata.explore.workbench;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CURATE stage: consistency validation over the last generated pool. Per served
 * type + model field, present/missing coverage and a verdict against the field's
 * {@link FieldExpectation} — which you set RIGHT HERE (the panel is the authoring
 * surface). Select a gappy field to drill into the members missing it, each linked to
 * Wikidata — the worklist an editor curates (next: enrich from another source, accept).
 */
public class CoveragePanel extends JPanel {

    private static final int MISSING_SHOWN = 500;
    private static final String[] EXPECT = {"none", "expected", "required"};

    private GeneratedProjectModel model;
    private boolean rebuilding;
    private final Map<String, List<WikidataDynamicObject>> byType = new LinkedHashMap<>();

    private final DefaultTableModel table = new DefaultTableModel(
            new Object[] {"Type", "Field", "Coverage", "Present", "Missing", "Expect", "Verdict"},
            0) {
        @Override public boolean isCellEditable(int r, int c) {
            return c == 5;   // only the Expect column is editable
        }
    };
    private final List<Row> rows = new ArrayList<>();
    private final JTable grid = new JTable(table);
    private final JLabel status = new JLabel(" ");

    private final DefaultListModel<Missing> missingModel = new DefaultListModel<>();
    private final JList<Missing> missingList = new JList<>(missingModel);
    private final JLabel missingHead = new JLabel("Select a field with a gap to drill in.");

    private record Row(String type, GeneratedFieldModel field, int present, int total) {}
    private record Missing(String name, String qid) {
        @Override public String toString() {
            return qid.matches("Q\\d+") ? name + "   (" + qid + " ↗)" : name;
        }
    }

    public CoveragePanel() {
        super(new BorderLayout(6, 6));

        grid.setRowHeight(22);
        grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        grid.getColumnModel().getColumn(6).setCellRenderer(new VerdictRenderer());
        TableColumn expect = grid.getColumnModel().getColumn(5);
        expect.setCellEditor(new javax.swing.DefaultCellEditor(
                new JComboBox<>(new DefaultComboBoxModel<>(EXPECT))));
        table.addTableModelListener(this::onExpectEdited);
        grid.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showMissing();
            }
        });

        JPanel top = new JPanel(new BorderLayout());
        top.add(new JScrollPane(grid), BorderLayout.CENTER);
        top.add(status, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(missingHead, BorderLayout.NORTH);
        bottom.add(new JScrollPane(missingList), BorderLayout.CENTER);
        missingList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent ev) {
                if (ev.getClickCount() == 2) {
                    openSelectedInWikidata();
                }
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.6);
        add(split, BorderLayout.CENTER);
    }

    /** Recompute coverage over the given pool + model (the last generation). */
    public void refresh(Collection<WikidataDynamicObject> pool, GeneratedProjectModel model) {
        this.model = model;
        byType.clear();
        rows.clear();
        table.setRowCount(0);
        missingModel.clear();
        missingHead.setText("Select a field with a gap to drill in.");
        if (pool == null || pool.isEmpty() || model == null) {
            status.setText("No generated pool — run \"Generate domain\" or \"Load saved instances\".");
            return;
        }
        for (WikidataDynamicObject o : pool) {
            String t = o == null ? null : o.typeName();
            if (t != null && model.findClass(t) != null) {
                byType.computeIfAbsent(t, k -> new ArrayList<>()).add(o);
            }
        }
        rebuild();
    }

    private void rebuild() {
        rebuilding = true;
        table.setRowCount(0);
        rows.clear();
        int gaps = 0;
        int violations = 0;
        for (Map.Entry<String, List<WikidataDynamicObject>> e : byType.entrySet()) {
            GeneratedClassModel c = model.findClass(e.getKey());
            List<WikidataDynamicObject> instances = e.getValue();
            for (GeneratedFieldModel f : c.fields()) {
                if (f == null || f.name() == null || "source".equals(f.name())) {
                    continue;
                }
                int present = 0;
                for (WikidataDynamicObject o : instances) {
                    if (has(o.get(f.name()))) {
                        present++;
                    }
                }
                int total = instances.size();
                String verdict = verdict(f.expectation(), total - present);
                if ("VIOLATION".equals(verdict)) {
                    violations++;
                } else if ("GAP".equals(verdict)) {
                    gaps++;
                }
                double pct = total == 0 ? 0 : Math.round(1000.0 * present / total) / 10.0;
                rows.add(new Row(e.getKey(), f, present, total));
                table.addRow(new Object[] {
                        e.getKey(), f.name(), pct + "%", present, total - present,
                        f.expectation().name().toLowerCase(), verdict});
            }
        }
        rebuilding = false;
        status.setText(violations + " violation(s), " + gaps + " gap(s) across "
                + byType.size() + " type(s).");
    }

    // Editing the Expect cell writes the expectation onto the model field (persisted on
    // "Save domain") and re-derives the verdict — the panel is the authoring surface.
    private void onExpectEdited(javax.swing.event.TableModelEvent e) {
        if (rebuilding || e.getColumn() != 5
                || e.getFirstRow() < 0 || e.getFirstRow() >= rows.size()) {
            return;
        }
        Row r = rows.get(e.getFirstRow());
        String chosen = String.valueOf(table.getValueAt(e.getFirstRow(), 5));
        r.field().expectation(FieldExpectation.valueOf(chosen.toUpperCase()));
        rebuild();
    }

    private void showMissing() {
        missingModel.clear();
        int i = grid.getSelectedRow();
        if (i < 0) {
            return;
        }
        Row r = rows.get(grid.convertRowIndexToModel(i));
        int gap = r.total() - r.present();
        if (gap <= 0) {
            missingHead.setText(r.type() + "." + r.field().name() + " — fully covered.");
            return;
        }
        int shown = 0;
        for (WikidataDynamicObject o : byType.getOrDefault(r.type(), List.of())) {
            if (!has(o.get(r.field().name()))) {
                missingModel.addElement(new Missing(o.getDisplayName(), o.qid()));
                if (++shown >= MISSING_SHOWN) {
                    break;
                }
            }
        }
        missingHead.setText(gap + " missing " + r.type() + "." + r.field().name()
                + " — showing " + shown + "  (double-click to open in Wikidata)");
    }

    private void openSelectedInWikidata() {
        Missing m = missingList.getSelectedValue();
        if (m == null || !m.qid().matches("Q\\d+") || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create("https://www.wikidata.org/wiki/" + m.qid()));
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static boolean has(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isBlank();
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }

    private static String verdict(FieldExpectation exp, int missing) {
        if (missing <= 0) {
            return "OK";
        }
        if (exp == FieldExpectation.REQUIRED) {
            return "VIOLATION";
        }
        if (exp == FieldExpectation.EXPECTED) {
            return "GAP";
        }
        return "OK";
    }

    private static final class VerdictRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            String v = String.valueOf(value);
            setForeground(sel ? getForeground()
                    : "VIOLATION".equals(v) ? new Color(0xB4, 0x23, 0x18)
                    : "GAP".equals(v) ? new Color(0xB7, 0x79, 0x1F)
                    : new Color(0x3F, 0xA4, 0x6A));
            return this;
        }
    }
}
