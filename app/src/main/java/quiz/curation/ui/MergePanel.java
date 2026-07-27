package quiz.curation.ui;

import objectview.render.CardListView;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import quiz.Quizable;
import quiz.curation.ManualCuration;
import quiz.curation.Merge;
import quiz.curation.Merges;
import quiz.transform.ui.DomainModel;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Merge curation: pick a member type, find two instances that are the same real entity
 * (search / sort help locate them), mark one PRIMARY and the other DUPLICATE, and Merge.
 * The duplicate's field values fold into the primary (union — the primary keeps its
 * identity and scalars, gains fields it lacked) and it leaves the pool. Persisted as a
 * {@link Merge} in the sidecar and re-applied after regeneration (see {@code quiz.curation}).
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

    // The card currently clicked; "Set primary"/"Set duplicate" capture it into a slot.
    private Quizable selected;
    private Quizable primary;
    private Quizable duplicate;

    public MergePanel(DomainModel domain, ManualCuration curation, Runnable onCurated) {
        this.domain = domain;
        this.curation = curation;
        this.onCurated = onCurated == null ? () -> { } : onCurated;

        setLayout(new BorderLayout(6, 6));
        add(top(), BorderLayout.NORTH);
        add(instancesHolder, BorderLayout.CENTER);
        add(bottom(), BorderLayout.SOUTH);

        for (String t : domain.types()) {
            typeCombo.addItem(t);
        }
        typeCombo.addActionListener(e -> refresh());
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

        JButton merge = new JButton("Merge ▶");
        merge.addActionListener(e -> doMerge());
        p.add(new JLabel("      "));
        p.add(merge);
        return p;
    }

    private void refreshSlots() {
        primaryLabel.setText(primary == null ? "none" : primary.getDisplayName());
        duplicateLabel.setText(duplicate == null ? "none" : duplicate.getDisplayName());
    }

    private void doMerge() {
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

        curation.putMerge(pid, did);
        try {
            curation.save();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            return;
        }
        Merges.apply(domain.instances(), List.of(new Merge(pid, did, Merge.MANUAL)));

        status.setText("   Merged \"" + duplicate.getDisplayName()
                + "\" into \"" + primary.getDisplayName() + "\"");
        primary = null;
        duplicate = null;
        refreshSlots();
        onCurated.run();   // re-render the main view with the merged instance
        refresh();         // the duplicate is gone from the pool
    }

    private void refresh() {
        String type = (String) typeCombo.getSelectedItem();
        List<Quizable> items = new ArrayList<>();
        if (type != null) {
            for (Quizable q : domain.instances()) {
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

    private JComponent instancesView(List<Quizable> items, String type) {
        CardListView v = new CardListView();

        RenderContext ctx = new RenderContext();
        ctx.setCollapsibleCards(true);
        ctx.setSelectionEnabled(true);
        ctx.addSelectionListener(o -> selected = o instanceof Quizable q ? q : null);
        v.setRenderContext(ctx);

        for (Quizable m : items) {
            v.addViewable(m);
        }
        v.createCardsPanel(1);

        JPanel panel = new JPanel(new BorderLayout());
        Quizable sample = items.isEmpty() ? null : items.get(0);
        if (sample != null) {
            @SuppressWarnings("unchecked")
            Class<? extends Quizable> cls = (Class<? extends Quizable>) sample.getClass();
            SearchPanel engine = new SearchPanel(cls, sample);
            engine.setHiddenFields(domain.structuralFields(type));
            engine.setFieldTypes(domain.fieldTypes(type));
            engine.setTarget(v.getCardsPanel(), v.getCardsScrollPane());
            v.addTargetListener(engine);
            panel.add(engine, BorderLayout.NORTH);
        } else {
            panel.add(new JLabel("   No instances of this type."), BorderLayout.NORTH);
        }
        panel.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        return panel;
    }
}
