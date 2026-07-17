package quiz.transform.ui;

import objectview.*;
import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.transform.pipeline.ui.ViewStepsPanel;

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

    private final JPanel renderHolder = new JPanel(new BorderLayout());

    private ViewStepsPanel viewStepsPanel;

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

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeft(), renderHolder);
        split.setResizeWeight(0.42);
        add(split, BorderLayout.CENTER);

        // ViewStepsPanel seeds the controller (and mirrors it into its controls);
        // render the seeded result once the panel is wired.
        render();
    }

    private JComponent buildLeft() {
        JPanel left = new JPanel(new BorderLayout(6, 6));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        if (controller.domain() instanceof SchemaView) {
            top.add(button("Schema…", this::showSchema));
        }
        if (controller.canSave()) {
            top.add(button("Save as domain…", this::saveAsDomain));
        }
        if (controller.domain() instanceof quiz.curation.Curatable c && c.curation() != null) {
            top.add(button("Curate…", () -> openCuration(c.curation())));
        }
        if (top.getComponentCount() > 0) {
            left.add(top, BorderLayout.NORTH);
        }

        viewStepsPanel = new ViewStepsPanel(controller, this::render);
        left.add(viewStepsPanel, BorderLayout.CENTER);

        return left;
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    /** Open the manual-curation panel over this domain; re-render on any change. */
    private void openCuration(quiz.curation.ManualCuration curation) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Curate — fill missing field values", Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.add(new quiz.curation.ui.CurationPanel(controller.domain(), curation, this::render),
                BorderLayout.CENTER);
        dialog.setSize(1000, 720);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Quizable> sampleClass(Quizable q) {
        return (Class<? extends Quizable>) q.getClass();
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
     *  for one type, or a per-class {@link MultiView} for several. */
    private JComponent flatView(List<Quizable> members, String type) {
        java.util.Map<String, List<Quizable>> byType = new java.util.LinkedHashMap<>();
        for (Quizable m : members) {
            if (m != null) {
                byType.computeIfAbsent(m.typeName(), k -> new ArrayList<>()).add(m);
            }
        }

        if (byType.size() <= 1) {
            CardListView v = new CardListView();
            for (Quizable m : members) {
                v.addViewable(m);
            }
            return searchableView(v, type);
        }

        MultiView mv = new MultiView();
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
     *  category ▸ year ▸ members — with a search / sort / fields bar above it (the
     *  data-centric counterpart to the flat view's SearchPanel). */
    private JComponent groupView(QuizableGroup root, String type) {
        Quizable sample = controller.sampleOf(type);
        Class<? extends Quizable> cls = sample != null ? sampleClass(sample) : Quizable.class;
        return new GroupTreeBrowser(root, cls, sample,
                                    controller.structuralFields(type), controller.fieldTypes(type));
    }

    /** Wraps a card view with the shared search + sort + view-config panel
     *  (sample-driven, so it's dynamic-aware + model-typed). */
    private JComponent searchableView(CardListView v, String type) {
        v.createCardsPanel(1);
        JPanel panel = new JPanel(new BorderLayout());
        Quizable sample = controller.sampleOf(type);
        if (sample != null) {
            SearchPanel engine =
                    new SearchPanel(sampleClass(sample), sample);
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
