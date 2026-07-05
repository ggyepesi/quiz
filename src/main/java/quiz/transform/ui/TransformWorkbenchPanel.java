package quiz.transform.ui;

import quiz.QuizableGroup;
import quiz.transform.View;
import quiz.transform.app.DomainSchema;
import quiz.transform.app.ViewSpecJsonIO;
import quiz.ui.QuizablePanelView;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural transform workbench over a loaded snapshot. Pick a member class, then
 * build a pipeline of operations: each operation's SIGNATURE narrows the fields
 * pane to only the fields that can be its argument (per shape), and every operation
 * compiles to a real {@link View} — filters and facet groupings — whose grouped
 * result (the derived subdomain) renders live on the right, reusing the same
 * card content view as the standalone view app.
 *
 * <p>Current ops (filter + group/invert) operate on the member class's own fields.
 * Multi-argument, cross-class operations (whose result is a new class fed back into
 * the field pool) build on the same signature/slot model.
 */
public final class TransformWorkbenchPanel extends JPanel {

    private final List<WikidataDynamicObject> pool;
    private final DomainSchema schema;

    private final JComboBox<String> memberTypeCombo = new JComboBox<>();
    private final JComboBox<OperationKind> operationCombo =
            new JComboBox<>(OperationKind.values());

    private final DefaultListModel<DomainField> fieldListModel = new DefaultListModel<>();
    private final JList<DomainField> fieldList = new JList<>(fieldListModel);

    private final JTextField valueField = new JTextField(14);

    private final List<OperationSpec> pipeline = new ArrayList<>();
    private final DefaultListModel<OperationSpec> pipelineModel = new DefaultListModel<>();
    private final JList<OperationSpec> pipelineList = new JList<>(pipelineModel);

    private final JPanel renderHolder = new JPanel(new BorderLayout());

    public TransformWorkbenchPanel(List<WikidataDynamicObject> pool, DomainSchema schema) {
        this.pool = pool;
        this.schema = schema;
        setLayout(new BorderLayout(8, 8));

        for (String t : schema.types()) {
            memberTypeCombo.addItem(t);
        }

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), renderHolder);
        split.setResizeWeight(0.42);
        add(split, BorderLayout.CENTER);

        memberTypeCombo.addActionListener(e -> { pipeline.clear(); pipelineModel.clear(); reloadFields(); render(); });
        operationCombo.addActionListener(e -> reloadFields());

        seedDefault();
        reloadFields();
        render();
    }

    private JComponent buildLeft() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Members:"));
        top.add(memberTypeCombo);
        top.add(new JLabel("Operation:"));
        top.add(operationCombo);

        JPanel fields = new JPanel(new BorderLayout(4, 4));
        fields.setBorder(BorderFactory.createTitledBorder("Fields — valid arguments for the operation"));
        fields.add(new JScrollPane(fieldList), BorderLayout.CENTER);
        JPanel argBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        argBar.add(new JLabel("Value:"));
        argBar.add(valueField);
        JButton add = new JButton("Add operation");
        add.addActionListener(e -> addOperation());
        argBar.add(add);
        fields.add(argBar, BorderLayout.SOUTH);

        JPanel steps = new JPanel(new BorderLayout(4, 4));
        steps.setBorder(BorderFactory.createTitledBorder("Pipeline (in order → derived subdomain)"));
        steps.add(new JScrollPane(pipelineList), BorderLayout.CENTER);
        JPanel stepBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        stepBar.add(button("Remove", () -> { int r = pipelineList.getSelectedIndex(); if (r >= 0) { pipeline.remove(r); refreshPipeline(); render(); } }));
        stepBar.add(button("Up", () -> move(-1)));
        stepBar.add(button("Down", () -> move(1)));
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

    /** Narrow the fields pane to the member class's fields that fit the operation's slot. */
    private void reloadFields() {
        fieldListModel.clear();
        String type = (String) memberTypeCombo.getSelectedItem();
        OperationKind kind = (OperationKind) operationCombo.getSelectedItem();
        if (type == null || kind == null) {
            return;
        }
        OperationSignature sig = OperationSignature.of(kind);
        valueField.setEnabled(sig.needsValue());
        for (String f : schema.fields(type)) {
            DomainField df = new DomainField(type, f,
                    schema.isReference(type, f), schema.isCollection(type, f));
            if (sig.fieldNeed().accepts(df)) {
                fieldListModel.addElement(df);
            }
        }
    }

    private void addOperation() {
        DomainField field = fieldList.getSelectedValue();
        OperationKind kind = (OperationKind) operationCombo.getSelectedItem();
        if (field == null || kind == null) {
            JOptionPane.showMessageDialog(this, "Select a field for the operation.");
            return;
        }
        Object value = OperationSignature.of(kind).needsValue()
                ? ViewSpecJsonIO.parseValue(valueField.getText().trim())
                : null;
        pipeline.add(new OperationSpec(kind, field, value));
        refreshPipeline();
        render();
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

    private void render() {
        String type = (String) memberTypeCombo.getSelectedItem();
        String name = (type == null ? "View" : type)
                + (pipeline.isEmpty() ? "" : " · " + pipeline.size() + " op");
        View view = ViewCompiler.compile(name, type, pipeline);
        QuizableGroup root = view.render(pool);

        QuizablePanelView v = new QuizablePanelView();
        v.addQuizable(root);
        v.createCardsPanel(1);

        renderHolder.removeAll();
        renderHolder.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        renderHolder.revalidate();
        renderHolder.repaint();
    }

    /** A ready-to-run "Oscar winners by category by year" if the snapshot supports it. */
    private void seedDefault() {
        if (!schema.types().contains("Nomination")) {
            return;
        }
        List<String> nf = schema.fields("Nomination");
        memberTypeCombo.setSelectedItem("Nomination");
        if (nf.contains("won")) {
            pipeline.add(new OperationSpec(OperationKind.FILTER,
                    new DomainField("Nomination", "won", false, false), Boolean.TRUE));
        }
        if (nf.contains("category")) {
            pipeline.add(new OperationSpec(OperationKind.GROUP_BY_REFERENCE,
                    new DomainField("Nomination", "category",
                            schema.isReference("Nomination", "category"),
                            schema.isCollection("Nomination", "category")), null));
        }
        if (nf.contains("year")) {
            pipeline.add(new OperationSpec(OperationKind.GROUP_BY_VALUE,
                    new DomainField("Nomination", "year", false, false), null));
        }
        refreshPipeline();
    }

    public static void main(String[] args) throws Exception {
        File snapshot = new File(args.length > 0 ? args[0]
                : "data/wikidata/oscarnominations/oscarnominations.snapshot.json");
        List<WikidataDynamicObject> pool =
                new WikidataDynamicObjectJsonStore().loadAll(snapshot);
        DomainSchema schema = new DomainSchema(pool);

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Transform Workbench — " + snapshot.getName());
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(new TransformWorkbenchPanel(pool, schema));
            f.setSize(1400, 900);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
