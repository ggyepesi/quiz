package quiz.transform.ui;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.transform.View;
import quiz.ui.QuizablePanelView;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural transform workbench over a {@link DomainModel} — a Wikidata snapshot or
 * a hand-written Quizable domain like Nobel / State / SportTeam
 * ({@link ReflectionDomain}). Pick a member class, then build a pipeline of
 * operations: each operation's SIGNATURE narrows the fields pane to only the fields
 * that can be its argument (per shape), and every operation compiles to a real
 * {@link View} — filters and facet groupings — whose grouped result (the derived
 * subdomain) renders live on the right via the shared card content view.
 *
 * <p>This package has NO backing dependency: saving a result goes through an
 * injected {@link DomainWriter}, and parsing a filter value through a small local
 * helper — so the workbench can be factored out and reused independently.
 */
public final class TransformWorkbenchPanel extends JPanel {

    private final WorkingDomain domain;
    private final DomainWriter writer;

    private final JComboBox<String> memberTypeCombo = new JComboBox<>();
    private final JComboBox<OperationKind> operationCombo =
            new JComboBox<>(OperationKind.values());

    // The fields pane is the SHARED rich field panel (nested/typed, expand/collapse,
    // no class-name repetition) — check the argument field(s) for the operation.
    private final JPanel fieldsHolder = new JPanel(new BorderLayout());
    private quiz.ui.viewconfig.QuizablePanelConfigEditor fieldEditor;
    private final JLabel slotHint = new JLabel(" ");

    private final JTextField valueField = new JTextField(12);
    private final JTextField newClassField = new JTextField(12);

    private final List<OperationSpec> pipeline = new ArrayList<>();
    private final DefaultListModel<OperationSpec> pipelineModel = new DefaultListModel<>();
    private final JList<OperationSpec> pipelineList = new JList<>(pipelineModel);

    private final JPanel renderHolder = new JPanel(new BorderLayout());

    public TransformWorkbenchPanel(DomainModel domain) {
        this(domain, null);
    }

    public TransformWorkbenchPanel(DomainModel domain, DomainWriter writer) {
        this.domain = new WorkingDomain(domain);
        this.writer = writer;
        setLayout(new BorderLayout(8, 8));

        for (String t : domain.types()) {
            memberTypeCombo.addItem(t);
        }

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), renderHolder);
        split.setResizeWeight(0.42);
        add(split, BorderLayout.CENTER);

        memberTypeCombo.addActionListener(e -> {
            pipeline.clear(); pipelineModel.clear();
            rebuildFieldEditor(); updateSlotUi(); render();
        });
        operationCombo.addActionListener(e -> updateSlotUi());

        seedDefault();
        rebuildFieldEditor();
        updateSlotUi();
        render();
    }

    private JComponent buildLeft() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Members:"));
        top.add(memberTypeCombo);
        top.add(new JLabel("Operation:"));
        top.add(operationCombo);

        JPanel fields = new JPanel(new BorderLayout(4, 4));
        fields.setBorder(BorderFactory.createTitledBorder("Fields — check the argument(s) for the operation"));
        fields.add(fieldsHolder, BorderLayout.CENTER);
        JPanel argBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        argBar.add(slotHint);
        argBar.add(new JLabel("Value:"));
        argBar.add(valueField);
        argBar.add(new JLabel("New class:"));
        argBar.add(newClassField);
        JButton add = new JButton("Add operation");
        add.addActionListener(e -> addOperation());
        argBar.add(add);
        fields.add(argBar, BorderLayout.SOUTH);

        JPanel steps = new JPanel(new BorderLayout(4, 4));
        steps.setBorder(BorderFactory.createTitledBorder("Pipeline (in order → derived subdomain)"));
        pipelineList.setCellRenderer(new PipelineRenderer());
        pipelineList.setFixedCellHeight(24);
        steps.add(new JScrollPane(pipelineList), BorderLayout.CENTER);
        JPanel stepBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        stepBar.add(button("Remove", () -> { int r = pipelineList.getSelectedIndex(); if (r >= 0) { pipeline.remove(r); refreshPipeline(); render(); } }));
        stepBar.add(button("Up", () -> move(-1)));
        stepBar.add(button("Down", () -> move(1)));
        stepBar.add(button("Save as domain…", this::saveAsDomain));
        steps.add(stepBar, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.add(top, BorderLayout.NORTH);
        JSplitPane inner = new JSplitPane(JSplitPane.VERTICAL_SPLIT, fields, steps);
        inner.setResizeWeight(0.6);
        left.add(inner, BorderLayout.CENTER);
        return left;
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Value/new-class enablement + the slot hint for the selected operation. */
    private void updateSlotUi() {
        OperationKind kind = (OperationKind) operationCombo.getSelectedItem();
        if (kind == null) {
            return;
        }
        OperationSignature sig = OperationSignature.of(kind);
        valueField.setEnabled(sig.needsValue());
        newClassField.setEnabled(sig.multiField());
        slotHint.setText(sig.multiField()
                ? "check the projected fields  ·"
                : "check a " + sig.fieldNeed() + " field  ·");
    }

    /** Rebuild the shared field panel for the selected member type. */
    private void rebuildFieldEditor() {
        String type = (String) memberTypeCombo.getSelectedItem();
        if (type == null) {
            return;
        }
        Quizable sample = sampleOf(type);
        fieldsHolder.removeAll();
        if (sample != null) {
            // allFields=false so nothing is pre-checked — the user checks the
            // field(s) to use as the operation's argument(s).
            quiz.ui.viewconfig.QuizablePanelConfig cfg =
                    quiz.ui.viewconfig.QuizablePanelConfig.of(sampleClass(sample));
            cfg.setAllFields(false);
            fieldEditor = new quiz.ui.viewconfig.QuizablePanelConfigEditor(cfg, sample);
            fieldsHolder.add(fieldEditor, BorderLayout.CENTER);
        } else {
            fieldEditor = null;
            fieldsHolder.add(new JLabel("  (no instances of " + type + ")"), BorderLayout.NORTH);
        }
        fieldsHolder.revalidate();
        fieldsHolder.repaint();
    }

    private Quizable sampleOf(String type) {
        for (Quizable q : domain.instances()) {
            if (q != null && type.equals(q.typeName())) {
                return q;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Quizable> sampleClass(Quizable q) {
        return (Class<? extends Quizable>) q.getClass();
    }

    /** A DomainField for a dotted path — shape from the domain (else scalar). */
    private DomainField field(String type, String path) {
        for (DomainField df : domain.fields(type)) {
            if (df.field().equals(path)) {
                return df;
            }
        }
        return new DomainField(type, path, false, false);
    }

    /** The field paths the user CHECKED in the shared panel. */
    private List<DomainField> checkedFields(String type) {
        if (fieldEditor == null) {
            return List.of();
        }
        List<DomainField> out = new ArrayList<>();
        for (String path : fieldEditor.selectedFieldPaths()) {
            out.add(field(type, path));
        }
        return out;
    }

    private void addOperation() {
        OperationKind kind = (OperationKind) operationCombo.getSelectedItem();
        String memberType = (String) memberTypeCombo.getSelectedItem();
        if (kind == null || memberType == null) {
            return;
        }

        List<DomainField> checked = checkedFields(memberType);

        // PROJECT is a DOMAIN mutation: materialize a new class from the checked
        // fields and feed it back into the pool — not a step in the view pipeline.
        if (kind == OperationKind.PROJECT_TO_CLASS) {
            String newType = newClassField.getText().trim();
            if (checked.isEmpty() || newType.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Check the fields to project and enter a new class name.");
                return;
            }
            DerivedClass derived = Projector.project(domain, memberType, checked, newType);
            domain.add(derived);
            if (!comboHas(memberTypeCombo, newType)) {
                memberTypeCombo.addItem(newType);
            }
            newClassField.setText("");
            JOptionPane.showMessageDialog(this, "Created class \"" + newType + "\"  ("
                    + derived.instances().size() + " instances, "
                    + derived.fields().size() + " fields) — select it as Members.");
            return;
        }

        if (checked.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Check one field for the operation.");
            return;
        }
        DomainField field = checked.get(0);
        // Validate the checked field fits the operation's slot shape.
        OperationSignature sig = OperationSignature.of(kind);
        if (!sig.fieldNeed().accepts(field)) {
            JOptionPane.showMessageDialog(this, "\"" + field.field() + "\" isn't a "
                    + sig.fieldNeed() + " field for " + kind + ".");
            return;
        }
        Object value = sig.needsValue() ? parseValue(valueField.getText().trim()) : null;
        pipeline.add(new OperationSpec(kind, field, value));
        refreshPipeline();
        render();
    }

    /** Parse a filter literal: true/false, int, double, else the trimmed string. */
    static Object parseValue(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim();
        if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        try { return Integer.valueOf(s); } catch (NumberFormatException ignored) { }
        try { return Double.valueOf(s); } catch (NumberFormatException ignored) { }
        return s;
    }

    /** Organized pipeline rows: "N.  <op>  field  = value" with a coloured op tag. */
    private static final class PipelineRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean sel, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
            if (value instanceof OperationSpec op && op.kind != null) {
                String tag; String color;
                switch (op.kind) {
                    case FILTER -> { tag = "filter"; color = "#b26a00"; }
                    case GROUP_BY_VALUE -> { tag = "group"; color = "#2f6fb0"; }
                    case GROUP_BY_REFERENCE -> { tag = "invert"; color = "#6a3fb0"; }
                    case PROJECT_TO_CLASS -> { tag = "project"; color = "#0a7a4a"; }
                    default -> { tag = "op"; color = "#555555"; }
                }
                String field = op.field == null ? "" : op.field.field();
                String val = op.kind == OperationKind.FILTER
                        ? " <font color='#999999'>= " + op.value + "</font>" : "";
                l.setText("<html><b>" + (index + 1) + ".</b> &nbsp;"
                        + "<font color='" + color + "'><b>" + tag + "</b></font> &nbsp;"
                        + "<font color='#222222'>" + field + "</font>" + val + "</html>");
            }
            l.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            return l;
        }
    }

    private static boolean comboHas(JComboBox<String> combo, String item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (item.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void move(int delta) {
        int r = pipelineList.getSelectedIndex();
        int n = r + delta;
        if (r >= 0 && n >= 0 && n < pipeline.size()) {
            OperationSpec tmp = pipeline.get(r);
            pipeline.set(r, pipeline.get(n));
            pipeline.set(n, tmp);
            refreshPipeline();
            pipelineList.setSelectedIndex(n);
            render();
        }
    }

    private void refreshPipeline() {
        pipelineModel.clear();
        for (OperationSpec op : pipeline) {
            pipelineModel.addElement(op);
        }
    }

    /** Compile + group OFF the EDT (a big domain can be slow), then swap in the
     *  rendered cards on the EDT. */
    private void render() {
        String type = (String) memberTypeCombo.getSelectedItem();
        String name = (type == null ? "View" : type)
                + (pipeline.isEmpty() ? "" : " · " + pipeline.size() + " op");
        List<OperationSpec> ops = new ArrayList<>(pipeline);

        renderHolder.removeAll();
        renderHolder.add(new JLabel("  Rendering…"), BorderLayout.NORTH);
        renderHolder.revalidate();
        renderHolder.repaint();

        new SwingWorker<QuizableGroup, Void>() {
            @Override protected QuizableGroup doInBackground() {
                View view = ViewCompiler.compile(name, type, ops, domain.universe());
                return view.render(domain.instances());
            }
            @Override protected void done() {
                try {
                    QuizableGroup root = get();
                    QuizablePanelView v = new QuizablePanelView();
                    v.addQuizable(root);
                    v.createCardsPanel(1);
                    renderHolder.removeAll();
                    renderHolder.add(v.getCardsScrollPane(), BorderLayout.CENTER);
                } catch (Exception ex) {
                    renderHolder.removeAll();
                    renderHolder.add(new JLabel("  Render failed: " + ex.getMessage()),
                            BorderLayout.NORTH);
                }
                renderHolder.revalidate();
                renderHolder.repaint();
            }
        }.execute();
    }

    /** Persist the current view's members (the filtered / projected result) as a
     *  first-class domain — a snapshot + registry entry the web serves and the
     *  navigator lists. */
    private void saveAsDomain() {
        String type = (String) memberTypeCombo.getSelectedItem();
        if (type == null) {
            return;
        }
        if (writer == null) {
            JOptionPane.showMessageDialog(this,
                    "No domain writer configured for this session.");
            return;
        }
        String suggested = type + (pipeline.isEmpty() ? "" : " view");
        String name = JOptionPane.showInputDialog(this,
                "Save the current result as a domain named:", suggested);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            View view = ViewCompiler.compile(name, type, pipeline, domain.universe());
            List<? extends Quizable> members = view.members(domain.instances());
            String message = writer.save(name, members);
            JOptionPane.showMessageDialog(this, message);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** A ready-to-run "Oscar winners by category by year" if the snapshot supports it. */
    private void seedDefault() {
        if (!domain.types().contains("Nomination")) {
            return;
        }
        memberTypeCombo.setSelectedItem("Nomination");
        DomainField won = field("Nomination", "won");
        DomainField category = field("Nomination", "category");
        DomainField year = field("Nomination", "year");
        if (won != null) {
            pipeline.add(new OperationSpec(OperationKind.FILTER, won, Boolean.TRUE));
        }
        if (category != null) {
            pipeline.add(new OperationSpec(OperationKind.GROUP_BY_REFERENCE, category, null));
        }
        if (year != null) {
            pipeline.add(new OperationSpec(OperationKind.GROUP_BY_VALUE, year, null));
        }
        refreshPipeline();
    }

    /** Open the workbench in a frame over any domain. */
    public static void launch(DomainModel domain, String title) {
        launch(domain, title, null);
    }

    public static void launch(DomainModel domain, String title, DomainWriter writer) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Transform Workbench — " + title);
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            f.add(new TransformWorkbenchPanel(domain, writer));
            f.setSize(1400, 900);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
