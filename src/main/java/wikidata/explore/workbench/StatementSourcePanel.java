package wikidata.explore.workbench;

import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.ModelStatementReifications.Reification;
import wikidata.explore.transform.QualifierLoadConfig;
import wikidata.explore.transform.ReifyConstruct;

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
    private GeneratedProjectModel projectModel;
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

    /** The project, so the read-out can call the real {@link
     *  ModelStatementReifications#deriveOne} (needs to resolve the source class). */
    public void setProjectModel(GeneratedProjectModel projectModel) {
        this.projectModel = projectModel;
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

    // The identity / subject-default fields / dedup key — rendered from the REAL
    // ModelStatementReifications.deriveOne (the same recipe the reify runs), so the
    // read-out can't drift from behaviour. Surfaces the subject-default fields (the
    // ones that silently become the source entity when their qualifier is absent —
    // the trap behind self-referential atoms like the Whale phantom).
    private void refreshDerived() {
        if (clazz == null) {
            return;
        }
        Reification r = projectModel == null
                ? null : ModelStatementReifications.deriveOne(clazz, projectModel);
        if (r == null) {
            identityValue.setText("statement id  (Q…-<guid>)");
            rolesValue.setText("—");
            dedupValue.setText("—");
            qualifiersArea.setText("  (not a reifying class yet — set \"Reify from\" + a "
                    + "statement property, and add fields with a \"Qualifier of\" PID)");
            return;
        }

        ReifyConstruct reify = r.reify();
        QualifierLoadConfig load = r.load();

        List<String> subjectDefault = new ArrayList<>();
        for (ReifyConstruct.Role role : reify.roles()) {
            if (role.fallbackToSource()) {
                subjectDefault.add(role.field());
            }
        }
        identityValue.setText("statement id  (Q…-<guid>)   ·   value = " + load.valueField()
                + (reify.canonicalizesByList()
                        ? "   ·   nominee-list = " + reify.primaryListField() : ""));
        rolesValue.setText(subjectDefault.isEmpty() ? "—" : String.join(", ", subjectDefault));
        dedupValue.setText(reify.dedupBy().isEmpty()
                ? "—" : String.join(" + ", reify.dedupBy()));

        StringBuilder quals = new StringBuilder();
        for (QualifierLoadConfig.Qualifier q : load.qualifiers()) {
            quals.append("  ").append(q.fieldName()).append("  ←  ").append(q.pid());
            if (q.multi()) {
                quals.append("  (list)");
            }
            if (q.kind() == QualifierLoadConfig.Kind.YEAR) {
                quals.append("  (date)");
            }
            quals.append('\n');
        }
        qualifiersArea.setText(quals.length() == 0
                ? "  (no qualifier fields yet — add fields with a \"Qualifier of\" PID)"
                : quals.toString());
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshDerived());
        buttons.add(refresh);

        // The identity/dedup key now flows through the class CanonicalSpec. A spec
        // saved from an earlier field set can go stale; re-derive clears it so the
        // key is inferred fresh from the CURRENT fields. (Explicit, so it never
        // silently changes an accepted dedup — you choose when to refresh it.)
        JButton rederive = new JButton("Re-derive identity");
        rederive.setToolTipText("Recompute the dedup/identity key from the current "
                + "fields (clears a saved CanonicalSpec that may be stale).");
        rederive.addActionListener(e -> {
            if (clazz != null) {
                clazz.canonical(null);
                refreshDerived();
                afterChange.accept(null);
            }
        });
        buttons.add(rederive);
        wide(form, c, y++, buttons);

        // Derived (read-only) — the reify definition at a glance.
        JPanel derived = new JPanel(new GridBagLayout());
        derived.setBorder(BorderFactory.createTitledBorder("Derived (read-only)"));
        GridBagConstraints dc = new GridBagConstraints();
        dc.insets = new Insets(3, 4, 3, 4);
        dc.anchor = GridBagConstraints.WEST;
        dc.fill = GridBagConstraints.HORIZONTAL;
        int dy = 0;
        row(derived, dc, dy++, "Identity:", identityValue);
        rolesValue.setToolTipText("Fields that take their qualifier value, ELSE the "
                + "SOURCE entity when the qualifier is absent. Right for the nominee "
                + "(the subject IS the nominee), but a reference like edition/forWork "
                + "collapsing to the source produces a self-referential atom.");
        row(derived, dc, dy++, "Subject-default:", rolesValue);
        dedupValue.setToolTipText("The identity key: two statements collapse to one "
                + "when these fields match. A bad subject-default silently poisons it.");
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
