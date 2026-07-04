package wikidata.explore.workbench;

import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.rule.RuleNode;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configures a STATEMENT class (statement-id identity): its instances are the
 * statements of a property on each member of a SOURCE class — e.g. Nomination =
 * the P1411 statements of OscarNominations. Distinct from {@link ClassSourcePanel}
 * (which configures qid-identity SOURCE classes), so the two identity models are
 * explicit. The qualifier→field mapping stays in {@link FieldSourcePanel}; this
 * panel owns the statement-level definition and shows the derived identity / roles
 * / dedup key, plus the (planned) person/work subclassing slot.
 */
public class StatementSourcePanel extends JPanel {

    private GeneratedClassModel clazz;
    private Consumer<Void> afterChange = v -> {};
    private Supplier<List<String>> sourceClassCandidates = List::of;

    private final JLabel titleLabel = new JLabel("Statement class");
    private final JTextField classNameField = new JTextField(18);
    private final JComboBox<String> reifyFromBox = new JComboBox<>();
    private final JTextField statementPropField = new JTextField("P1411", 6);
    private final JTextField valueTypeField = new JTextField(10);

    private final JLabel identityValue = new JLabel(" ");
    private final JLabel rolesValue = new JLabel(" ");
    private final JLabel dedupValue = new JLabel(" ");
    private final JTextArea qualifiersArea = new JTextArea(4, 28);

    public StatementSourcePanel() {
        buildUi();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    public void sourceClassCandidates(Supplier<List<String>> candidates) {
        this.sourceClassCandidates = candidates == null ? List::of : candidates;
    }

    public void edit(GeneratedClassModel clazz) {
        this.clazz = clazz;
        if (clazz == null) {
            titleLabel.setText("Statement class");
            classNameField.setText("");
            qualifiersArea.setText("");
            return;
        }
        titleLabel.setText("Statement class: " + clazz.className());
        classNameField.setText(clazz.className());

        reifyFromBox.removeAllItems();
        for (String name : sourceClassCandidates.get()) {
            if (name != null && !name.isBlank() && !name.equals(clazz.className())) {
                reifyFromBox.addItem(name);
            }
        }
        reifyFromBox.setSelectedItem(clazz.statementSourceClass());

        FieldSourceMapping m = clazz.instanceMapping();
        statementPropField.setText(m.propertyPid().isBlank() ? "P1411" : m.propertyPid());
        valueTypeField.setText(m.sourceQid());

        refreshDerived();
    }

    public void applyEdits() {
        if (clazz == null) {
            return;
        }
        clazz.className(classNameField.getText().trim());
        Object src = reifyFromBox.getSelectedItem();
        clazz.statementSourceClass(src == null ? "" : src.toString().trim());

        FieldSourceMapping m = clazz.instanceMapping();
        m.propertyPid(RuleNode.cleanPid(statementPropField.getText()));
        m.sourceQid(RuleNode.cleanQid(valueTypeField.getText()));

        refreshDerived();
        afterChange.accept(null);
    }

    // The identity / roles / dedup key the reify will derive — read-only, so the
    // whole statement definition reads in one place. Mirrors
    // ModelStatementReifications.derive at a glance.
    private void refreshDerived() {
        if (clazz == null) {
            return;
        }
        String statementProp = RuleNode.cleanPid(statementPropField.getText());
        StringBuilder quals = new StringBuilder();
        List<String> roles = new ArrayList<>();
        List<String> dedup = new ArrayList<>();
        String valueField = null;

        for (GeneratedFieldModel f : clazz.fields()) {
            if (f == null || f.isNameField() || f.mapping() == null) {
                continue;
            }
            FieldSourceMapping fm = f.mapping();
            // Derived fields (COMPANION_MATCH / INVERT) aren't statement qualifiers.
            boolean derived = fm.productionKind() != FieldProductionKind.AUTO;
            String qual = RuleNode.cleanPid(fm.qualifierPid());
            String prop = RuleNode.cleanPid(fm.propertyPid());

            if (!derived && prop.equals(statementProp) && valueField == null) {
                valueField = f.name();                 // the ps: value field
            } else if (!derived && qual.matches("(?i)P\\d+")) {
                quals.append("  ").append(f.name()).append("  ←  ").append(qual).append('\n');
                if (f.type() != FieldType.DATE) {      // dates are attributes, not identity
                    dedup.add(f.name());
                }
                if (f.type() == FieldType.ENTITY) {
                    roles.add(f.name());               // subject-fallback role
                }
            }
        }
        if (valueField != null) {
            dedup.add(0, valueField);
        }

        qualifiersArea.setText(quals.length() == 0
                ? "  (no qualifier fields yet — add fields with a \"Qualifier of\" PID)"
                : quals.toString());
        identityValue.setText("statement id  (Q…-<guid>)");
        rolesValue.setText(roles.isEmpty() ? "—" : String.join(", ", roles));
        dedupValue.setText(dedup.isEmpty() ? "—" : String.join(" + ", dedup));
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        wide(form, c, y++, titleLabel);

        JLabel q = new JLabel("Instances are the statements of a property on each "
                + "member of a source class.");
        q.setFont(q.getFont().deriveFont(Font.ITALIC));
        wide(form, c, y++, q);

        row(form, c, y++, "Class name:", classNameField);

        reifyFromBox.setToolTipText("The SOURCE class whose statements are reified "
                + "into this class (e.g. OscarNominations).");
        row(form, c, y++, "Reify from:", reifyFromBox);

        statementPropField.setToolTipText("The statement property whose statements "
                + "become instances (e.g. P1411 nominated for).");
        row(form, c, y++, "Statement property:", statementPropField);

        valueTypeField.setToolTipText("Optional P31 filter on the statement VALUE — "
                + "keep only statements whose value is instance-of this (e.g. Q19020 "
                + "Academy Awards, to drop Grammy categories sharing P1411).");
        row(form, c, y++, "Value type filter:", valueTypeField);

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshDerived());
        wide(form, c, y++, refresh);

        // Derived (read-only) — the reify definition at a glance.
        JPanel derived = new JPanel(new GridBagLayout());
        derived.setBorder(BorderFactory.createTitledBorder("Derived (read-only)"));
        GridBagConstraints dc = new GridBagConstraints();
        dc.insets = new Insets(3, 4, 3, 4);
        dc.anchor = GridBagConstraints.WEST;
        dc.fill = GridBagConstraints.HORIZONTAL;
        int dy = 0;
        row(derived, dc, dy++, "Identity:", identityValue);
        row(derived, dc, dy++, "Roles:", rolesValue);
        row(derived, dc, dy++, "Dedup key:", dedupValue);
        qualifiersArea.setEditable(false);
        qualifiersArea.setBorder(BorderFactory.createTitledBorder("Qualifier fields"));
        derived.add(qualifiersArea, gbc(0, dy, 2));
        wide(form, c, y++, derived);

        // Subclassing slot (planned): person vs work by a role field's type.
        JPanel sub = new JPanel(new BorderLayout());
        sub.setBorder(BorderFactory.createTitledBorder("Subclasses (planned)"));
        JLabel subNote = new JLabel("<html>Split by a role field's type — e.g. "
                + "<b>nominee.P31 = human</b> → PersonNomination vs WorkNomination. "
                + "Uses the class's baseClassName + discriminator; UI coming.</html>");
        sub.add(subNote, BorderLayout.CENTER);
        wide(form, c, y++, sub);

        // Push content up.
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0; filler.gridy = y; filler.weighty = 1.0;
        filler.fill = GridBagConstraints.BOTH; filler.gridwidth = 2;
        form.add(new JLabel(), filler);

        setLayout(new BorderLayout());
        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    private static void row(JPanel form, GridBagConstraints c, int y,
                            String label, JComponent field) {
        GridBagConstraints lc = (GridBagConstraints) c.clone();
        lc.gridx = 0; lc.gridy = y; lc.weightx = 0;
        form.add(new JLabel(label), lc);
        GridBagConstraints fc = (GridBagConstraints) c.clone();
        fc.gridx = 1; fc.gridy = y; fc.weightx = 1.0;
        form.add(field, fc);
    }

    private static void wide(JPanel form, GridBagConstraints c, int y, JComponent comp) {
        form.add(comp, gbc(0, y, 2));
    }

    private static GridBagConstraints gbc(int x, int y, int w) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x; g.gridy = y; g.gridwidth = w;
        g.insets = new Insets(4, 4, 4, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        return g;
    }
}
