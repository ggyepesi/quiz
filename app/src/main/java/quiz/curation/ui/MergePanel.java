package quiz.curation.ui;

import objectview.ViewableAdapter;
import objectview.field.FieldAccess;
import objectview.field.FieldRef;
import objectview.field.FieldSet;
import objectview.Viewable;
import quiz.curation.ManualCuration;
import quiz.curation.Merge;
import quiz.curation.Mergeable;
import quiz.curation.Merges;
import domain.DomainModel;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Merge curation: pick a member type, find two instances that are the same real entity
 * (search / sort help locate them), mark one PRIMARY and the other DUPLICATE, then
 * preview the field-by-field result and approve. For each field the curator chooses the
 * source — primary, duplicate, or both (union of collections/maps) — defaulting to
 * fill-empty / union / primary-wins. On approve the duplicate's values fold into the
 * primary and it leaves the pool. Persisted as a {@link Merge} in the sidecar and
 * re-applied after regeneration (see {@code quiz.curation}).
 */
public final class MergePanel extends JPanel {

    private final DomainModel domain;
    private final ManualCuration curation;
    private final Runnable onCurated;

    private final JComboBox<String> typeCombo = new JComboBox<>();
    private final JLabel primaryLabel = new JLabel("none");
    private final JLabel duplicateLabel = new JLabel("none");
    private final JLabel status = new JLabel(" ");
    private final JPanel instancesHolder = new JPanel(new BorderLayout());
    private final JButton mergeButton = new JButton("Preview & merge ▶");

    // The card currently clicked; "Set primary"/"Set duplicate" capture it into a slot.
    private Viewable selected;
    private Viewable primary;
    private Viewable duplicate;

    public MergePanel(DomainModel domain, ManualCuration curation, Runnable onCurated) {
        this.domain = domain;
        this.curation = curation;
        this.onCurated = onCurated == null ? () -> { } : onCurated;

        setLayout(new BorderLayout(6, 6));
        add(top(), BorderLayout.NORTH);
        add(instancesHolder, BorderLayout.CENTER);
        add(bottom(), BorderLayout.SOUTH);

        Collection<? extends Viewable> candidates = mergeableInstances();
        for (String t : domain.types()) {
            boolean present = candidates.stream().anyMatch(q -> q != null && t.equals(q.typeName()));
            if (present) {
                typeCombo.addItem(t);
            }
        }
        typeCombo.addActionListener(e -> {
            selected = null;
            primary = null;
            duplicate = null;
            refreshSlots();
            refresh();
        });
        if (typeCombo.getItemCount() > 0) {
            typeCombo.setSelectedIndex(0);
        }
        refresh();
    }

    private JComponent top() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.add(new JLabel("Type:"));
        p.add(typeCombo);
        p.add(status);
        return p;
    }

    private JComponent bottom() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        JButton setPrimary = new JButton("Set primary");
        setPrimary.addActionListener(e -> {
            if (selected != null) { primary = selected; refreshSlots(); }
        });
        p.add(setPrimary);
        primaryLabel.setForeground(new Color(0, 110, 40));
        p.add(primaryLabel);

        JButton setDuplicate = new JButton("Set duplicate");
        setDuplicate.addActionListener(e -> {
            if (selected != null) { duplicate = selected; refreshSlots(); }
        });
        p.add(new JLabel("      "));
        p.add(setDuplicate);
        duplicateLabel.setForeground(new Color(150, 60, 0));
        p.add(duplicateLabel);

        mergeButton.addActionListener(e -> previewAndApply());
        mergeButton.setEnabled(false);   // only two distinct instances enable it
        mergeButton.setToolTipText("Set a primary and a DIFFERENT duplicate to enable");
        p.add(new JLabel("      "));
        p.add(mergeButton);
        return p;
    }

    private void refreshSlots() {
        primaryLabel.setText(primary == null ? "none" : primary.getDisplayName());
        duplicateLabel.setText(duplicate == null ? "none" : duplicate.getDisplayName());
        mergeButton.setEnabled(mergeable());
    }

    /** Two distinct instances of the same type — the only state in which a merge is
     *  valid, so the button stays disabled until then (no silent same-instance merge). */
    private boolean mergeable() {
        if (primary == null || duplicate == null) {
            return false;
        }
        String pid = primary.getIdentifier();
        String did = duplicate.getIdentifier();
        return pid != null && did != null && !pid.equals(did)
                && java.util.Objects.equals(primary.typeName(), duplicate.typeName());
    }

    // ------------------------------------------------------------------
    // Preview + approve
    // ------------------------------------------------------------------

    private void previewAndApply() {
        if (primary == null || duplicate == null) {
            status.setText("   Set both a primary and a duplicate (click a card, then the button)");
            return;
        }
        String pid = primary.getIdentifier();
        String did = duplicate.getIdentifier();
        if (pid == null || did == null || pid.equals(did)) {
            status.setText("   Primary and duplicate must be different instances");
            return;
        }
        if (!primary.typeName().equals(duplicate.typeName())) {
            status.setText("   Primary and duplicate must have the same type");
            return;
        }

        List<FieldChoice> rows = planRows(primary, duplicate);

        JPanel grid = new JPanel(new GridBagLayout());
        addHeader(grid);
        Map<String, JComboBox<String>> choosers = new LinkedHashMap<>();
        int r = 1;
        for (FieldChoice fc : rows) {
            JComboBox<String> combo = new JComboBox<>(fc.options().toArray(new String[0]));
            combo.setSelectedItem(fc.def());
            combo.setEnabled(fc.options().size() > 1);
            choosers.put(fc.field(), combo);
            addRow(grid, r++, fc, combo);
        }

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setPreferredSize(new Dimension(760, Math.min(480, 70 + rows.size() * 28)));

        String title = "Merge preview — fold \"" + duplicate.getDisplayName()
                + "\" into \"" + primary.getDisplayName() + "\"";
        int res = JOptionPane.showConfirmDialog(this, scroll, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            return;
        }

        Map<String, String> fieldSource = new LinkedHashMap<>();
        for (FieldChoice fc : rows) {
            fieldSource.put(fc.field(), (String) choosers.get(fc.field()).getSelectedItem());
        }
        applyMerge(primary.typeName(), pid, did, fieldSource);
    }

    private void applyMerge(
            String type, String pid, String did, Map<String, String> fieldSource) {
        String pName = primary.getDisplayName();
        String dName = duplicate.getDisplayName();

        Merge merge = new Merge(type, pid, did, fieldSource, Merge.MANUAL);
        int n;
        try {
            n = domain instanceof Mergeable mg
                    ? mg.applyMerge(merge)
                    : Merges.apply(
                            domain.instances(), List.of(merge), domain::baseType);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Merge failed: " + ex.getMessage());
            return;
        }
        if (n == 0) {
            status.setText("   Merge had no effect (instances not found in the pool)");
            return;
        }

        // putMerge also reconciles identity (survivor inherits primary→secondary; the
        // loser's link is dropped), since identity lives in the curation, not the instance.
        curation.putMerge(type, pid, did, fieldSource);
        try {
            curation.save();
        } catch (Exception ex) {
            curation.removeMerge(type, did);
            JOptionPane.showMessageDialog(this,
                    "The merge is active in this session but could not be saved.\n"
                            + "Reloading restores the original data.\n\n" + ex.getMessage());
        }

        status.setText("   Merged \"" + dName + "\" into \"" + pName + "\"");
        primary = null;
        duplicate = null;
        refreshSlots();
        onCurated.run();   // re-render the main view with the merged instance
        refresh();         // the duplicate is gone from the pool
    }

    /** The per-field resolution the preview offers: primary/duplicate values, the
     *  choices allowed, and the default source. */
    private record FieldChoice(String field, Object primaryVal, Object dupVal,
                               List<String> options, String def) { }

    private List<FieldChoice> planRows(Viewable p, Viewable d) {
        List<String> names = new ArrayList<>();
        for (FieldRef ref : FieldSet.of(p).fields()) {
            names.add(ref.name());
        }
        for (FieldRef ref : FieldSet.of(d).fields()) {
            if (!names.contains(ref.name())) {
                names.add(ref.name());
            }
        }

        List<FieldChoice> out = new ArrayList<>();
        for (String name : names) {
            Object pv = FieldAccess.getPath(p, name);
            Object dv = FieldAccess.getPath(d, name);
            boolean pValid = ViewableAdapter.isValidQuizValue(pv);
            boolean dValid = ViewableAdapter.isValidQuizValue(dv);
            if (!pValid && !dValid) {
                continue;   // nothing to decide
            }

            boolean multi = pv instanceof Collection || pv instanceof Map
                    || dv instanceof Collection || dv instanceof Map;
            List<String> opts = new ArrayList<>();
            String def;
            if (pValid && dValid) {
                if (multi) {
                    opts.add(Merge.BOTH);
                    opts.add(Merge.PRIMARY);
                    opts.add(Merge.DUPLICATE);
                    def = Merge.BOTH;
                } else if (String.valueOf(pv).equals(String.valueOf(dv))) {
                    opts.add(Merge.PRIMARY);
                    def = Merge.PRIMARY;   // identical — no conflict
                } else {
                    opts.add(Merge.PRIMARY);
                    opts.add(Merge.DUPLICATE);
                    def = Merge.PRIMARY;   // conflict — primary wins by default
                }
            } else if (pValid) {
                opts.add(Merge.PRIMARY);
                def = Merge.PRIMARY;
            } else {
                opts.add(Merge.DUPLICATE);
                def = Merge.DUPLICATE;
            }
            out.add(new FieldChoice(name, pv, dv, opts, def));
        }
        return out;
    }

    private static String display(Object v) {
        if (v == null) {
            return "—";
        }
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                return "—";
            }
            String values = c.stream().limit(5)
                    .map(MergePanel::label)
                    .collect(Collectors.joining(", "));
            return values + (c.size() > 5 ? " … (" + c.size() + ")" : "");
        }
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "—";
            }
            String values = m.entrySet().stream().limit(5)
                    .map(e -> label(e.getKey()) + " → " + label(e.getValue()))
                    .collect(Collectors.joining(", "));
            return values + (m.size() > 5 ? " … (" + m.size() + ")" : "");
        }
        String s = label(v);
        if (s.isEmpty()) {
            return "—";
        }
        return s.length() > 44 ? s.substring(0, 44) + "…" : s;
    }

    private static String label(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof Viewable q) {
            String name = q.getDisplayName();
            return name == null || name.isBlank() ? q.typeName() : name;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "—" : text;
    }

    private void addHeader(JPanel grid) {
        String[] cols = {"Field", "Primary", "Duplicate", "Take"};
        for (int c = 0; c < cols.length; c++) {
            JLabel l = new JLabel(cols[c]);
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            grid.add(l, gbc(c, 0));
        }
    }

    private void addRow(JPanel grid, int row, FieldChoice fc, JComboBox<String> combo) {
        grid.add(new JLabel(fc.field()), gbc(0, row));
        grid.add(dim(new JLabel(display(fc.primaryVal()))), gbc(1, row));
        grid.add(dim(new JLabel(display(fc.dupVal()))), gbc(2, row));
        grid.add(combo, gbc(3, row));
    }

    private static JLabel dim(JLabel l) {
        l.setForeground(Color.GRAY);
        return l;
    }

    private static GridBagConstraints gbc(int x, int y) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(2, 8, 2, 8);
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = x == 3 ? 0 : 1;
        return g;
    }

    // ------------------------------------------------------------------
    // Instance browser
    // ------------------------------------------------------------------

    private void refresh() {
        String type = (String) typeCombo.getSelectedItem();
        List<Viewable> items = new ArrayList<>();
        if (type != null) {
            for (Viewable q : mergeableInstances()) {
                if (q != null && type.equals(q.typeName())) {
                    items.add(q);
                }
            }
        }
        status.setText("   " + items.size() + (type == null ? "" : " " + type));
        selected = null;

        instancesHolder.removeAll();
        instancesHolder.add(instancesView(items, type), BorderLayout.CENTER);
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    private JComponent instancesView(List<Viewable> items, String type) {
        Viewable sample = items.isEmpty() ? null : items.get(0);
        return objectview.view.SearchableView.builder(items)
                .sample(sample)
                .hiddenFields(domain.structuralFields(type))
                .fieldTypes(domain.fieldTypes(type))
                .fieldSchemas(q -> domain.fieldSchema(q.typeName()))
                .collapsible(true)
                .selectionListener(o -> selected = o instanceof Viewable q ? q : null)
                .emptyMessage("No instances of this type.")
                .build();
    }

    private Collection<? extends Viewable> mergeableInstances() {
        return domain instanceof Mergeable mergeable
                ? mergeable.mergeableInstances()
                : domain.instances();
    }
}
