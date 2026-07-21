package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
import quiz.Quizable;

import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consistency validation in the transform app — the front of the CURATE stage. Runs over
 * the WHOLE working schema the domain exposes: base classes, DERIVED classes (project /
 * join) and facets alike — per field, how many instances carry a value vs. how many are
 * missing. Select a gappy field to drill into the members missing it, each linked to
 * Wikidata: the worklist curation acts on (a manual fill, or a source enrichment).
 */
public final class ValidationPanel extends JPanel {

    private static final int MISSING_SHOWN = 500;

    private final DomainModel domain;
    private final DefaultTableModel table = new DefaultTableModel(
            new Object[] {"Type", "Field", "Coverage", "Present", "Missing"}, 0) {
        @Override public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final List<Row> rows = new ArrayList<>();
    private final JTable grid = new JTable(table);
    private final JLabel status = new JLabel(" ");

    private final DefaultListModel<Missing> missingModel = new DefaultListModel<>();
    private final JList<Missing> missingList = new JList<>(missingModel);
    private final JLabel missingHead = new JLabel("Select a field with a gap to drill in.");
    private final Map<String, List<Quizable>> byType = new LinkedHashMap<>();

    private record Row(String type, String path) {}
    private record Missing(String name, String qid) {
        @Override public String toString() {
            return qid != null && qid.matches("Q\\d+") ? name + "   (" + qid + " ↗)" : name;
        }
    }

    public ValidationPanel(DomainModel domain) {
        super(new BorderLayout(6, 6));
        this.domain = domain;

        grid.setRowHeight(22);
        grid.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        grid.setAutoCreateRowSorter(true);
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
                    openInWikidata();
                }
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.6);
        add(split, BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        table.setRowCount(0);
        rows.clear();
        byType.clear();
        missingModel.clear();
        missingHead.setText("Select a field with a gap to drill in.");

        for (Quizable q : domain.instances()) {
            if (q != null && q.typeName() != null) {
                byType.computeIfAbsent(q.typeName(), k -> new ArrayList<>()).add(q);
            }
        }
        int gaps = 0;
        for (String type : domain.types()) {
            List<Quizable> instances = byType.getOrDefault(type, List.of());
            if (instances.isEmpty()) {
                continue;
            }
            Set<String> structural = domain.structuralFields(type);
            Set<String> seen = new HashSet<>();
            for (DomainField f : domain.fields(type)) {
                String path = f.field();
                if (path == null || structural.contains(path) || !seen.add(path)) {
                    continue;
                }
                int present = 0;
                for (Quizable q : instances) {
                    if (has(q, path)) {
                        present++;
                    }
                }
                int total = instances.size();
                if (total - present > 0) {
                    gaps++;
                }
                double pct = total == 0 ? 0 : Math.round(1000.0 * present / total) / 10.0;
                rows.add(new Row(type, path));
                table.addRow(new Object[] {type, path, pct + "%", present, total - present});
            }
        }
        status.setText(gaps + " field(s) with gaps across " + byType.size() + " type(s).");
    }

    private void showMissing() {
        missingModel.clear();
        int i = grid.getSelectedRow();
        if (i < 0) {
            return;
        }
        Row r = rows.get(grid.convertRowIndexToModel(i));
        int shown = 0;
        int gap = 0;
        for (Quizable q : byType.getOrDefault(r.type(), List.of())) {
            if (!has(q, r.path())) {
                gap++;
                if (shown < MISSING_SHOWN) {
                    missingModel.addElement(new Missing(q.getDisplayName(), q.getIdentifier()));
                    shown++;
                }
            }
        }
        missingHead.setText(gap == 0
                ? r.type() + "." + r.path() + " — fully covered."
                : gap + " missing " + r.type() + "." + r.path() + " — showing " + shown
                        + "  (double-click to open in Wikidata)");
    }

    private void openInWikidata() {
        Missing m = missingList.getSelectedValue();
        if (m == null || m.qid() == null || !m.qid().matches("Q\\d+")
                || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create("https://www.wikidata.org/wiki/" + m.qid()));
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static boolean has(Quizable q, String path) {
        List<Object> current = new ArrayList<>();
        current.add(q);
        for (String seg : path.split("\\.")) {
            List<Object> next = new ArrayList<>();
            for (Object o : current) {
                if (o instanceof Viewable v) {
                    Object val = FieldSet.of(v).read(seg);
                    if (val instanceof Collection<?> c) {
                        next.addAll(c);
                    } else if (val != null) {
                        next.add(val);
                    }
                }
            }
            current = next;
        }
        for (Object o : current) {
            if (o == null) {
                continue;
            }
            if (o instanceof String s && s.isBlank()) {
                continue;
            }
            if (o instanceof Collection<?> c && c.isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }
}
