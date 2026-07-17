package quiz.curation.ui;

import objectview.ViewablePanelView;
import objectview.ViewableSearchPanel;
import quiz.Quizable;
import quiz.curation.Corrections;
import quiz.curation.ManualCuration;
import quiz.transform.pipeline.ui.FilterCondition;
import quiz.transform.pipeline.ui.FilterOperator;
import quiz.transform.pipeline.ui.FilterPredicates;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import objectview.ViewableRenderContext;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual curation: pick a member type + a scalar field, see the instances MISSING it
 * (reusing the {@code IS_EMPTY} filter + the shared ViewablePanel / ViewableSearchPanel
 * card view), enter a value for one, and Set. Saved values persist to the sidecar and
 * re-apply after regeneration (see {@code quiz.curation}). Values are stored plainly
 * and coerced to the field's type on apply, so e.g. a typed year fills a DATE field.
 */
public final class CurationPanel extends JPanel {

    private final DomainModel domain;
    private final ManualCuration curation;
    private final Runnable onCurated;

    private final JComboBox<String> typeCombo = new JComboBox<>();
    private final JComboBox<FieldItem> fieldCombo = new JComboBox<>();
    private final JTextField valueField = new JTextField(12);
    private final JLabel status = new JLabel(" ");
    private final JLabel selectedLabel = new JLabel("no instance selected");
    private final JPanel instancesHolder = new JPanel(new BorderLayout());

    // The instance to fill — chosen by clicking its card in the view above
    // (search / sort help find it), not from a combo.
    private Quizable selected;

    public CurationPanel(DomainModel domain, ManualCuration curation, Runnable onCurated) {
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
        typeCombo.addActionListener(e -> onTypeChanged());
        fieldCombo.addActionListener(e -> refresh());

        if (typeCombo.getItemCount() > 0) {
            typeCombo.setSelectedIndex(0);
            onTypeChanged();
        }
    }

    private JComponent top() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.add(new JLabel("Type:"));
        p.add(typeCombo);
        p.add(new JLabel("Field:"));
        p.add(fieldCombo);
        p.add(status);
        return p;
    }

    private JComponent bottom() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.add(new JLabel("Selected:"));
        selectedLabel.setForeground(Color.GRAY);
        p.add(selectedLabel);
        p.add(new JLabel("   value:"));
        p.add(valueField);
        JButton set = new JButton("Set");
        set.addActionListener(e -> setValue());
        p.add(set);
        return p;
    }

    private void onSelected(Quizable q) {
        selected = q;
        if (q == null) {
            selectedLabel.setText("no instance selected");
            selectedLabel.setForeground(Color.GRAY);
        } else {
            selectedLabel.setText(q.getDisplayName());
            selectedLabel.setForeground(new Color(0, 110, 40));
        }
    }

    private void onTypeChanged() {
        fieldCombo.removeAllItems();
        String type = (String) typeCombo.getSelectedItem();
        if (type != null) {
            java.util.Set<String> structural = domain.structuralFields(type);
            for (DomainField f : domain.fields(type)) {
                // Scalars only for now — references/collections need entity picking.
                if (!f.reference() && !f.collection()
                        && !f.field().contains(".")
                        && !structural.contains(f.field())) {
                    fieldCombo.addItem(new FieldItem(f));
                }
            }
        }
        refresh();
    }

    private void refresh() {
        String type = (String) typeCombo.getSelectedItem();
        FieldItem fi = (FieldItem) fieldCombo.getSelectedItem();

        List<Quizable> missing = new ArrayList<>();
        if (type != null && fi != null) {
            FilterCondition empty =
                    new FilterCondition(fi.field, FilterOperator.IS_EMPTY, null, null);
            for (Quizable q : domain.instances()) {
                if (q != null && type.equals(q.typeName()) && FilterPredicates.matches(q, empty)) {
                    missing.add(q);
                }
            }
        }

        status.setText("   " + missing.size() + " missing"
                + (fi == null ? "" : " " + fi.field.field()));

        onSelected(null);   // the list changed — drop any prior selection

        instancesHolder.removeAll();
        instancesHolder.add(instancesView(missing, type), BorderLayout.CENTER);
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    private void setValue() {
        FieldItem fi = (FieldItem) fieldCombo.getSelectedItem();
        if (fi == null) {
            return;
        }
        if (selected == null) {
            status.setText("   Select an instance (click its card) first");
            return;
        }
        String value = valueField.getText().trim();
        if (value.isEmpty()) {
            return;
        }

        // Store the plain value; Corrections coerces it to the field's type on apply.
        curation.put(selected.getIdentifier(), fi.field.field(), value);
        try {
            curation.save();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
            return;
        }
        Corrections.apply(domain.instances(), List.of(curation));

        valueField.setText("");
        onCurated.run();   // re-render the main view with the filled value
        refresh();         // the instance now has a value → drops from the missing list
    }

    private JComponent instancesView(List<Quizable> missing, String type) {
        ViewablePanelView v = new ViewablePanelView();

        // Enable click-to-select on the cards: clicking an instance's name
        // selects it (green ring), and Set fills the selected instance. The
        // context must be set before the cards are built so they pick it up.
        ViewableRenderContext ctx = new ViewableRenderContext();
        ctx.setCollapsibleCards(true);   // birdseye: cards start collapsed, drill in at will
        ctx.setSelectionEnabled(true);
        ctx.addSelectionListener(o -> onSelected(o instanceof Quizable q ? q : null));
        v.setRenderContext(ctx);

        for (Quizable m : missing) {
            v.addQuizable(m);
        }
        v.createCardsPanel(1);

        JPanel panel = new JPanel(new BorderLayout());
        Quizable sample = missing.isEmpty() ? null : missing.get(0);
        if (sample != null) {
            @SuppressWarnings("unchecked")
            Class<? extends Quizable> cls = (Class<? extends Quizable>) sample.getClass();
            ViewableSearchPanel engine = new ViewableSearchPanel(cls, sample);
            engine.setHiddenFields(domain.structuralFields(type));
            engine.setFieldTypes(domain.fieldTypes(type));
            engine.setTarget(v.getCardsPanel(), v.getCardsScrollPane());
            v.addTargetListener(engine);
            panel.add(engine, BorderLayout.NORTH);
        } else {
            panel.add(new JLabel("   No instances are missing this field."), BorderLayout.NORTH);
        }
        panel.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        return panel;
    }

    private record FieldItem(DomainField field) {
        @Override public String toString() { return field.field(); }
    }
}
