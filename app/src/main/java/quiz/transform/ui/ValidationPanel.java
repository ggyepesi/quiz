package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSet;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import quiz.Quizable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consistency validation in the transform app — the front of the CURATE stage. Runs over
 * the WHOLE working schema the domain exposes (base classes, DERIVED classes and facets):
 * per field, how many instances carry a value vs. how many are missing. Select a gappy
 * field to drill into the members missing it — rendered with the shared {@link CardListView}
 * / {@link SearchPanel} (selectable cards, searchable), so a missing member IS the object,
 * ready for curation (a manual fill or a source enrichment).
 */
public final class ValidationPanel extends JPanel {

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

    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    private final Map<String, List<Quizable>> byType = new LinkedHashMap<>();
    private Quizable selected;

    private record Row(String type, String path) {}

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

        instancesHolder.add(
                new JLabel("   Select a field with a gap to drill into its missing members."),
                BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, instancesHolder);
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);
        refresh();
    }

    /** The last selected missing member — the target a curation/enrichment action fills. */
    public Quizable selected() {
        return selected;
    }

    public void refresh() {
        table.setRowCount(0);
        rows.clear();
        byType.clear();
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
        selected = null;
        instancesHolder.removeAll();
        int i = grid.getSelectedRow();
        if (i < 0) {
            instancesHolder.revalidate();
            instancesHolder.repaint();
            return;
        }
        Row r = rows.get(grid.convertRowIndexToModel(i));
        List<Quizable> missing = new ArrayList<>();
        for (Quizable q : byType.getOrDefault(r.type(), List.of())) {
            if (!has(q, r.path())) {
                missing.add(q);
            }
        }
        instancesHolder.add(header(r, missing.size()), BorderLayout.NORTH);
        if (!missing.isEmpty()) {
            instancesHolder.add(instancesView(missing, r.type()), BorderLayout.CENTER);
        }
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    private JLabel header(Row r, int gap) {
        return new JLabel(gap == 0
                ? "   " + r.type() + "." + r.path() + " — fully covered."
                : "   " + gap + " member(s) missing " + r.type() + "." + r.path());
    }

    // The shared instance rendering: selectable, searchable cards (same components the
    // curation panel uses), so a missing member is the object — click to select it.
    private JComponent instancesView(List<Quizable> missing, String type) {
        CardListView v = new CardListView();
        RenderContext ctx = new RenderContext();
        ctx.setCollapsibleCards(true);
        ctx.setSelectionEnabled(true);
        ctx.addSelectionListener(o -> selected = o instanceof Quizable q ? q : null);
        v.setRenderContext(ctx);
        for (Quizable m : missing) {
            v.addViewable(m);
        }
        v.createCardsPanel(1);

        JPanel panel = new JPanel(new BorderLayout());
        Quizable sample = missing.get(0);
        @SuppressWarnings("unchecked")
        Class<? extends Quizable> cls = (Class<? extends Quizable>) sample.getClass();
        SearchPanel engine = new SearchPanel(cls, sample);
        engine.setHiddenFields(domain.structuralFields(type));
        engine.setFieldTypes(domain.fieldTypes(type));
        engine.setTarget(v.getCardsPanel(), v.getCardsScrollPane());
        v.addTargetListener(engine);
        panel.add(engine, BorderLayout.NORTH);
        panel.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        return panel;
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
