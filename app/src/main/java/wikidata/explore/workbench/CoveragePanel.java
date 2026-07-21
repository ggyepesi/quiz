package wikidata.explore.workbench;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The front half of the CURATE stage: per served type, per model field, how many
 * generated instances actually carry a value — and a verdict against the field's
 * declared {@link FieldExpectation} (REQUIRED-missing = VIOLATION, EXPECTED-missing =
 * GAP, else OK). A GAP is the worklist an editor curates (enrich from another source,
 * then accept). Read straight off the last generation's pool, in the authoring app —
 * validation belongs to the model→transform→curate→viewconfig flow, not the web serve.
 */
public class CoveragePanel extends JPanel {

    private final DefaultTableModel table = new DefaultTableModel(
            new Object[] {"Type", "Field", "Coverage", "Present", "Missing", "Expect", "Verdict"},
            0) {
        @Override public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JLabel status = new JLabel(" ");

    public CoveragePanel() {
        super(new BorderLayout(6, 6));
        JTable t = new JTable(table);
        t.setRowHeight(22);
        t.setAutoCreateRowSorter(true);
        t.getColumnModel().getColumn(6).setCellRenderer(new VerdictRenderer());
        add(new JScrollPane(t), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    /** Recompute coverage over the given pool + model (the last generation). */
    public void refresh(Collection<WikidataDynamicObject> pool, GeneratedProjectModel model) {
        table.setRowCount(0);
        if (pool == null || pool.isEmpty() || model == null) {
            status.setText("No generated pool — run \"Generate domain\" first.");
            return;
        }
        // Instances grouped by their stamped, modeled class.
        Map<String, List<WikidataDynamicObject>> byType = new LinkedHashMap<>();
        for (WikidataDynamicObject o : pool) {
            String t = o == null ? null : o.typeName();
            if (t != null && model.findClass(t) != null) {
                byType.computeIfAbsent(t, k -> new ArrayList<>()).add(o);
            }
        }

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
                int missing = total - present;
                FieldExpectation exp = f.expectation();
                String verdict = verdict(exp, missing);
                if ("VIOLATION".equals(verdict)) {
                    violations++;
                } else if ("GAP".equals(verdict)) {
                    gaps++;
                }
                double pct = total == 0 ? 0 : Math.round(1000.0 * present / total) / 10.0;
                table.addRow(new Object[] {
                        e.getKey(), f.name(), pct + "%", present, missing,
                        exp == FieldExpectation.NONE ? "—" : exp.name().toLowerCase(), verdict});
            }
        }
        status.setText(violations + " violation(s), " + gaps + " gap(s) across "
                + byType.size() + " type(s) — mark a field EXPECTED/REQUIRED to flag its gaps.");
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
