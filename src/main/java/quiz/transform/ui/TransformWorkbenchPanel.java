package quiz.transform.ui;

import quiz.Quizable;
import quiz.QuizableGroup;
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
 * {@link quiz.transform.View} — filters and facet groupings — whose grouped result
 * (the derived subdomain) renders live on the right via the shared card content view.
 *
 * <p>This is a THIN Swing view: all logic — the {@link WorkingDomain}, the pipeline,
 * compiling the result, saving — lives in {@link TransformController}. This class
 * owns only the widgets, forwards user actions to the controller, and turns the
 * controller's {@link QuizableGroup} result into cards.
 */
public final class TransformWorkbenchPanel extends JPanel {

    private final TransformController controller;

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
    // JOIN: match this (left) class to a right class on a key.
    private final JComboBox<String> rightClassCombo = new JComboBox<>();
    private final JComboBox<String> rightKeyCombo = new JComboBox<>();

    private final DefaultListModel<OperationSpec> pipelineModel = new DefaultListModel<>();
    private final JList<OperationSpec> pipelineList = new JList<>(pipelineModel);

    private final JPanel renderHolder = new JPanel(new BorderLayout());

    // Suppresses the member-combo listener during programmatic selection (seeding),
    // so syncing the widget to the controller doesn't reset the just-seeded pipeline.
    private boolean syncing;

    // Bumped on every render() (EDT-only). A background render swaps its cards in
    // only if it's still the latest — so a slow earlier render can't overwrite a
    // newer one that finished first.
    private int renderGeneration;

    public TransformWorkbenchPanel(DomainModel domain) {
        this(domain, null);
    }

    public TransformWorkbenchPanel(DomainModel domain, DomainWriter writer) {
        this.controller = new TransformController(domain, writer);
        setLayout(new BorderLayout(8, 8));

        for (String t : controller.types()) {
            memberTypeCombo.addItem(t);
        }

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), renderHolder);
        split.setResizeWeight(0.42);
        add(split, BorderLayout.CENTER);

        memberTypeCombo.addActionListener(e -> {
            if (syncing) {
                return;
            }
            controller.selectType((String) memberTypeCombo.getSelectedItem());
            refreshPipeline();
            rebuildFieldEditor();
            updateSlotUi();
            render();
        });
        operationCombo.addActionListener(e -> updateSlotUi());

        if (controller.seedDefault()) {
            selectMemberType(controller.selectedType());
        } else if (memberTypeCombo.getItemCount() > 0) {
            controller.selectType((String) memberTypeCombo.getSelectedItem());
        }
        refreshPipeline();
        rebuildFieldEditor();
        updateSlotUi();
        render();
    }

    /** Set the member combo without firing its listener (keeps the controller's
     *  pipeline, which we may have just seeded). */
    private void selectMemberType(String type) {
        syncing = true;
        memberTypeCombo.setSelectedItem(type);
        syncing = false;
    }

    private JComponent buildLeft() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Members:"));
        top.add(memberTypeCombo);
        top.add(new JLabel("Operation:"));
        top.add(operationCombo);
        // Only when the domain can show its compiled schema (ModelClass ↔ ProductClass).
        if (controller.domain() instanceof SchemaView) {
            top.add(button("Schema…", this::showSchema));
        }

        JPanel fields = new JPanel(new BorderLayout(4, 4));
        fields.setBorder(BorderFactory.createTitledBorder("Fields — check the argument(s) for the operation"));
        fields.add(fieldsHolder, BorderLayout.CENTER);
        JPanel argBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        argBar.add(slotHint);
        argBar.add(new JLabel("Value:"));
        argBar.add(valueField);
        argBar.add(new JLabel("Join right:"));
        argBar.add(rightClassCombo);
        argBar.add(new JLabel("on key:"));
        argBar.add(rightKeyCombo);
        argBar.add(new JLabel("New class:"));
        argBar.add(newClassField);
        JButton add = new JButton("Add operation");
        add.addActionListener(e -> addOperation());
        argBar.add(add);
        fields.add(argBar, BorderLayout.SOUTH);

        for (String t : controller.types()) {
            rightClassCombo.addItem(t);
        }
        rightClassCombo.addActionListener(e -> reloadRightKeys());
        reloadRightKeys();

        JPanel steps = new JPanel(new BorderLayout(4, 4));
        steps.setBorder(BorderFactory.createTitledBorder("Pipeline (in order → derived subdomain)"));
        pipelineList.setCellRenderer(new PipelineRenderer());
        pipelineList.setFixedCellHeight(24);
        steps.add(new JScrollPane(pipelineList), BorderLayout.CENTER);
        JPanel stepBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        stepBar.add(button("Remove", () -> {
            int r = pipelineList.getSelectedIndex();
            if (r >= 0) {
                controller.removeOperation(r);
                refreshPipeline();
                render();
            }
        }));
        stepBar.add(button("Up", () -> move(-1)));
        stepBar.add(button("Down", () -> move(1)));
        stepBar.add(button("Nest ⇄ Indep", () -> {
            if (controller.toggleGroupNesting(pipelineList.getSelectedIndex())) {
                refreshPipeline();
                render();
            }
        }));
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
        boolean join = kind == OperationKind.JOIN;
        valueField.setEnabled(sig.needsValue());
        rightClassCombo.setEnabled(join);
        rightKeyCombo.setEnabled(join);
        newClassField.setEnabled(sig.multiField() || join);
        slotHint.setText(join ? "check the LEFT key  ·"
                : sig.multiField() ? "check the projected fields  ·"
                : sig.fieldNeed() == OperationSignature.Need.ANY ? "check a field  ·"
                : "check a " + sig.fieldNeed() + " field  ·");
    }

    private void reloadRightKeys() {
        rightKeyCombo.removeAllItems();
        String rt = (String) rightClassCombo.getSelectedItem();
        if (rt == null) {
            return;
        }
        for (DomainField df : controller.fields(rt)) {
            rightKeyCombo.addItem(df.field());
        }
    }

    /** Rebuild the shared field panel for the selected member type. */
    private void rebuildFieldEditor() {
        String type = controller.selectedType();
        if (type == null) {
            return;
        }
        Quizable sample = controller.sampleOf(type);
        fieldsHolder.removeAll();
        if (sample != null) {
            // allFields=false so nothing is pre-checked — the user checks the
            // field(s) to use as the operation's argument(s).
            quiz.ui.viewconfig.QuizablePanelConfig cfg =
                    quiz.ui.viewconfig.QuizablePanelConfig.of(sampleClass(sample));
            cfg.setAllFields(false);
            fieldEditor = new quiz.ui.viewconfig.QuizablePanelConfigEditor(cfg, sample);
            // The domain's structural fields (provenance/plumbing) aren't offered
            // as arguments — the DomainModel seam says which, this panel just obeys.
            fieldEditor.setHiddenFields(controller.structuralFields(type));
            // Authoritative model types (labels, cardinality, nested structural
            // hiding) when the domain is compiled; null reflects the sample.
            fieldEditor.setFieldTypes(controller.fieldTypes(type));
            fieldsHolder.add(fieldEditor, BorderLayout.CENTER);
        } else {
            fieldEditor = null;
            fieldsHolder.add(new JLabel("  (no instances of " + type + ")"), BorderLayout.NORTH);
        }
        fieldsHolder.revalidate();
        fieldsHolder.repaint();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Quizable> sampleClass(Quizable q) {
        return (Class<? extends Quizable>) q.getClass();
    }

    /** The field paths the user CHECKED in the shared panel. */
    private List<String> checkedPaths() {
        return fieldEditor == null ? List.of() : fieldEditor.selectedFieldPaths();
    }

    private void addOperation() {
        OperationKind kind = (OperationKind) operationCombo.getSelectedItem();
        String type = controller.selectedType();
        List<DomainField> checked = controller.resolveFields(
                type == null ? "" : type, checkedPaths());

        TransformController.OpOutcome outcome = controller.addOperation(
                kind, checked, valueField.getText(),
                (String) rightClassCombo.getSelectedItem(),
                (String) rightKeyCombo.getSelectedItem(),
                newClassField.getText().trim());

        if (!outcome.ok()) {
            JOptionPane.showMessageDialog(this, outcome.message());
            return;
        }

        // A materialized class (PROJECT / JOIN): offer it in the type pickers.
        if (outcome.createdType() != null) {
            String newType = outcome.createdType();
            if (!comboHas(memberTypeCombo, newType)) {
                memberTypeCombo.addItem(newType);
            }
            if (outcome.addToRightClass() && !comboHas(rightClassCombo, newType)) {
                rightClassCombo.addItem(newType);
            }
            newClassField.setText("");
            JOptionPane.showMessageDialog(this, outcome.message());
            return;
        }

        // A plain pipeline step.
        refreshPipeline();
        render();
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
                    case GROUP_BY -> { tag = "group"; color = "#2f6fb0"; }
                    case PROJECT_TO_CLASS -> { tag = "project"; color = "#0a7a4a"; }
                    case JOIN -> { tag = "join"; color = "#a03050"; }
                    default -> { tag = "op"; color = "#555555"; }
                }
                // A FILTER shows its whole AND predicate; other ops show the field.
                String body = op.kind == OperationKind.FILTER
                        ? op.filterText()
                        : (op.field == null ? "" : op.field.field());
                l.setText("<html><b>" + (index + 1) + ".</b> &nbsp;"
                        + "<font color='" + color + "'><b>" + tag + "</b></font> &nbsp;"
                        + "<font color='#222222'>" + body + "</font></html>");
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
        int n = controller.moveOperation(pipelineList.getSelectedIndex(), delta);
        if (n >= 0) {
            refreshPipeline();
            pipelineList.setSelectedIndex(n);
            render();
        }
    }

    private void refreshPipeline() {
        pipelineModel.clear();
        for (OperationSpec op : controller.pipeline()) {
            pipelineModel.addElement(op);
        }
    }

    /** Compile + group OFF the EDT (a big domain can be slow), then swap in the
     *  rendered cards on the EDT. */
    private void render() {
        // Capture the generation + inputs on the EDT so a slow render can be
        // discarded if superseded, and the worker never reads the pipeline while
        // it's being mutated.
        final int generation = ++renderGeneration;
        final String type = controller.selectedType();
        final List<OperationSpec> ops = controller.pipeline();

        renderHolder.removeAll();
        renderHolder.add(new JLabel("  Rendering…"), BorderLayout.NORTH);
        renderHolder.revalidate();
        renderHolder.repaint();

        new SwingWorker<QuizableGroup, Void>() {
            @Override protected QuizableGroup doInBackground() {
                return controller.compileResult(type, ops);
            }
            @Override protected void done() {
                if (generation != renderGeneration) {
                    return;   // a newer render started — don't overwrite it
                }
                try {
                    QuizableGroup root = get();
                    renderHolder.removeAll();
                    // A grouped (facet) result keeps its group structure; a flat one
                    // shows its members as per-class instance sections, like the
                    // modelbuilder — a section per type present in the result.
                    renderHolder.add(root.getChildren().isEmpty()
                            ? flatView(new ArrayList<>(root.getMembers()), type)
                            : groupView(root, type), BorderLayout.CENTER);
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

    /** A flat result: members grouped by type — a single searchable instance view
     *  for one type, or a per-class {@link quiz.ui.MultiQuizableView} for several. */
    private JComponent flatView(List<Quizable> members, String type) {
        java.util.Map<String, List<Quizable>> byType = new java.util.LinkedHashMap<>();
        for (Quizable m : members) {
            if (m != null) {
                byType.computeIfAbsent(m.typeName(), k -> new ArrayList<>()).add(m);
            }
        }

        if (byType.size() <= 1) {
            QuizablePanelView v = new QuizablePanelView();
            for (Quizable m : members) {
                v.addQuizable(m);
            }
            return searchableView(v, type);
        }

        quiz.ui.MultiQuizableView mv = new quiz.ui.MultiQuizableView();
        for (java.util.Map.Entry<String, List<Quizable>> e : byType.entrySet()) {
            String t = e.getKey();
            List<Quizable> objs = e.getValue();
            mv.addSection(t, sampleClass(objs.get(0)), objs,
                    objs.get(0), controller.structuralFields(t), controller.fieldTypes(t));
        }
        mv.build(1);
        return mv;
    }

    /** A grouped (facet) result: a role-aware collapsible outline of the buckets —
     *  category ▸ year ▸ members — not the raw QuizableGroup structure. */
    private JComponent groupView(QuizableGroup root, String type) {
        JScrollPane scroll = new JScrollPane(new quiz.ui.GroupTreeView(root));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** Wraps a card view with the shared search + sort + view-config panel
     *  (sample-driven, so it's dynamic-aware + model-typed). */
    private JComponent searchableView(QuizablePanelView v, String type) {
        v.createCardsPanel(1);
        JPanel panel = new JPanel(new BorderLayout());
        Quizable sample = controller.sampleOf(type);
        if (sample != null) {
            quiz.ui.QuizableSearchPanel engine =
                    new quiz.ui.QuizableSearchPanel(sampleClass(sample), sample);
            engine.setHiddenFields(controller.structuralFields(type));
            engine.setFieldTypes(controller.fieldTypes(type));
            engine.setTarget(v.getCardsPanel(), v.getCardsScrollPane());
            v.addTargetListener(engine);
            panel.add(engine, BorderLayout.NORTH);
        }
        panel.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        return panel;
    }

    /** Persist the current view's members (the filtered / projected result) as a
     *  first-class domain — a snapshot + registry entry the web serves and the
     *  navigator lists. */
    private void saveAsDomain() {
        String type = controller.selectedType();
        if (type == null) {
            return;
        }
        if (!controller.canSave()) {
            JOptionPane.showMessageDialog(this,
                    "No domain writer configured for this session.");
            return;
        }
        String suggested = type + (controller.pipeline().isEmpty() ? "" : " view");
        String name = JOptionPane.showInputDialog(this,
                "Save the current result as a domain named:", suggested);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            JOptionPane.showMessageDialog(this, controller.saveAsDomain(name));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Show the compiled-schema inspector (ModelClass ↔ ProductClass) in a dialog. */
    private void showSchema() {
        JComponent view = controller.domain() instanceof SchemaView sv ? sv.schemaView() : null;
        if (view == null) {
            return;
        }
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Schema — ModelClass ↔ ProductClass", Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.add(view, BorderLayout.CENTER);
        dialog.setSize(900, 560);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
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
