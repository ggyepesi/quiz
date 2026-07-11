package quiz.curation.ui;

import quiz.Quizable;
import quiz.curation.Corrections;
import quiz.curation.ManualCuration;
import quiz.transform.pipeline.ui.FilterCondition;
import quiz.transform.pipeline.ui.FilterOperator;
import quiz.transform.pipeline.ui.FilterPredicates;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.ui.QuizablePanelView;
import quiz.ui.QuizableSearchPanel;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual curation: pick a member type + a scalar field, see the instances MISSING it
 * (reusing the {@code IS_EMPTY} filter + the shared QuizablePanel / QuizableSearchPanel
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
    private final JComboBox<InstanceItem> targetCombo = new JComboBox<>();
    private final JTextField valueField = new JTextField(12);
    private final JLabel status = new JLabel(" ");
    private final JPanel instancesHolder = new JPanel(new BorderLayout());

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
        p.add(new JLabel("Set for:"));
        p.add(targetCombo);
        p.add(new JLabel("value:"));
        p.add(valueField);
        JButton set = new JButton("Set");
        set.addActionListener(e -> setValue());
        p.add(set);
        return p;
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

        targetCombo.removeAllItems();
        for (Quizable q : missing) {
            targetCombo.addItem(new InstanceItem(q));
        }

        instancesHolder.removeAll();
        instancesHolder.add(instancesView(missing, type), BorderLayout.CENTER);
        instancesHolder.revalidate();
        instancesHolder.repaint();
    }

    private void setValue() {
        InstanceItem target = (InstanceItem) targetCombo.getSelectedItem();
        FieldItem fi = (FieldItem) fieldCombo.getSelectedItem();
        if (target == null || fi == null) {
            return;
        }
        String value = valueField.getText().trim();
        if (value.isEmpty()) {
            return;
        }

        // Store the plain value; Corrections coerces it to the field's type on apply.
        curation.put(target.q.getIdentifier(), fi.field.field(), value);
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
        QuizablePanelView v = new QuizablePanelView();
        for (Quizable m : missing) {
            v.addQuizable(m);
        }
        v.createCardsPanel(1);

        JPanel panel = new JPanel(new BorderLayout());
        Quizable sample = missing.isEmpty() ? null : missing.get(0);
        if (sample != null) {
            @SuppressWarnings("unchecked")
            Class<? extends Quizable> cls = (Class<? extends Quizable>) sample.getClass();
            QuizableSearchPanel engine = new QuizableSearchPanel(cls, sample);
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

    private record InstanceItem(Quizable q) {
        @Override public String toString() {
            return q.getDisplayName() + "   (" + q.getIdentifier() + ")";
        }
    }
}
