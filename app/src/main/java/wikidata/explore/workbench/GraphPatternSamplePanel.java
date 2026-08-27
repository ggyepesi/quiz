package wikidata.explore.workbench;

import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import datasource.graph.GraphExpansionPolicy;
import objectview.Viewable;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import quiz.transform.DynamicViewable;
import wikidata.WikidataIds;
import wikidata.explore.generation.WikidataGraphDiscoveryState;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.query.logical.GraphPatternSampleQuery;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only Explore surface explaining a configured graph expansion on bounded data. */
final class GraphPatternSamplePanel extends JPanel {
    private record Choice(String statementClass, GraphExpansionPattern pattern) {
        @Override public String toString() {
            return statementClass + " · " + pattern.relation().relationId()
                    + " · " + pattern.sourceNodeClass() + " → "
                    + pattern.targetNodeClass();
        }
    }

    private final GeneratedProjectModel model;
    private SwingQueryRunner runner;
    private boolean wired;
    private final JComboBox<Choice> patterns = new JComboBox<>();
    private final JTextField seeds = new JTextField(24);
    private final JSpinner limit = new JSpinner(new SpinnerNumberModel(60, 1, 200, 10));
    private final JButton preview = new JButton("Preview expansion");
    private final JLabel status = new JLabel(" ");
    private final PatternDiagram diagram = new PatternDiagram();
    private final JTabbedPane results = new JTabbedPane();
    private String seededFor;

    GraphPatternSamplePanel(GeneratedProjectModel model) {
        super(new BorderLayout(6, 6));
        this.model = java.util.Objects.requireNonNull(model, "model");
        buildUi();
        refreshPatterns();
    }

    void setQueryRunner(SwingQueryRunner value) {
        runner = value;
        if (wired || runner == null) return;
        wired = true;
        runner.wireButton(preview, this::accept, this::query,
                error -> status.setText("Preview failed: " + message(error)));
        updateEnabled();
    }

    void refreshPatterns() {
        String selected = patterns.getSelectedItem() instanceof Choice choice
                ? choice.statementClass() : "";
        patterns.removeAllItems();
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz.statementSource() == null
                    || clazz.statementSource().graphExpansionPolicy()
                    != GraphExpansionPolicy.CURATED) continue;
            GraphExpansionPattern pattern = WikidataGraphDiscoveryState
                    .structuralPattern(model, clazz.className());
            if (pattern != null) patterns.addItem(new Choice(clazz.className(), pattern));
        }
        for (int i = 0; i < patterns.getItemCount(); i++) {
            if (selected.equals(patterns.getItemAt(i).statementClass())) {
                patterns.setSelectedIndex(i);
                break;
            }
        }
        choosePattern();
    }

    private void buildUi() {
        JPanel controls = new JPanel(new GridBagLayout());
        controls.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4); c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        objectview.utils.swing.GridBagUtils.labeledRow(controls, c, 0,
                "Pattern:", patterns);
        seeds.setToolTipText("One or more configured target-node QIDs, separated by spaces.");
        objectview.utils.swing.GridBagUtils.labeledRow(controls, c, 1,
                "Expansion nodes:", seeds);
        objectview.utils.swing.GridBagUtils.labeledRow(controls, c, 2,
                "Maximum statements:", limit);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(preview); buttons.add(status);
        objectview.utils.swing.GridBagUtils.wideRow(controls, 3, buttons);
        JLabel help = new JLabel("<html>Read-only sample: selected target nodes → "
                + "reverse subjects → all configured statements → newly encountered "
                + "target nodes. Nothing is added to the model or snapshot.</html>");
        objectview.utils.swing.GridBagUtils.wideRow(controls, 4, help);
        patterns.addActionListener(event -> choosePattern());

        JPanel north = new JPanel(new BorderLayout(4, 4));
        north.add(controls, BorderLayout.NORTH);
        north.add(diagram, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        results.addTab("Selected nodes (0)", empty("Run a preview."));
        results.addTab("Sources (0)", empty("Run a preview."));
        results.addTab("Statements (0)", empty("Run a preview."));
        results.addTab("Frontier (0)", empty("Run a preview."));
        add(results, BorderLayout.CENTER);
    }

    private void choosePattern() {
        Choice choice = patterns.getSelectedItem() instanceof Choice c ? c : null;
        if (choice == null) {
            seededFor = null;
            seeds.setText("");
            status.setText("No enabled, structurally complete graph pattern.");
            diagram.pattern(null);
            updateEnabled();
            return;
        }
        // Prefilling is for a pattern the reader has just switched to. This also runs
        // on every unrelated model-tree selection, and overwriting the box there would
        // discard a QID they typed to explore — along with the last preview's status.
        if (!choice.pattern().id().equals(seededFor)) {
            seededFor = choice.pattern().id();
            GeneratedClassModel target =
                    model.findClass(choice.pattern().targetNodeClass());
            List<String> qids = target == null ? List.of() : target.seedQids().stream()
                    .filter(WikidataIds::isQid).limit(3).toList();
            seeds.setText(String.join(" ", qids));
            status.setText(qids.isEmpty() ? "This pattern has no expansion nodes." : " ");
        }
        diagram.pattern(choice.pattern());
        updateEnabled();
    }

    private GraphPatternSampleQuery query() {
        Choice choice = patterns.getSelectedItem() instanceof Choice c ? c : null;
        if (choice == null) return null;
        List<String> qids = java.util.Arrays.stream(seeds.getText().split("[,;\\s]+"))
                .map(String::trim).filter(WikidataIds::isQid).distinct().toList();
        return new GraphPatternSampleQuery(choice.pattern(), qids,
                ((Number) limit.getValue()).intValue());
    }

    private void accept(GraphPatternSampleQuery.Result sample) {
        Choice choice = patterns.getSelectedItem() instanceof Choice c ? c : null;
        if (choice == null || sample == null) return;
        Map<String, DynamicViewable> selected = views(sample.selected(),
                choice.pattern().targetNodeClass());
        Map<String, DynamicViewable> sources = views(sample.sources(),
                choice.pattern().sourceNodeClass());
        Map<String, DynamicViewable> targets = views(sample.targets(),
                choice.pattern().targetNodeClass());
        List<Viewable> statementCards = new ArrayList<>();
        for (GraphPatternSampleQuery.Edge edge : sample.statements()) {
            DynamicViewable card = new DynamicViewable(edge.statementId(),
                    edge.source().label() + " — " + edge.target().label());
            card.type(choice.pattern().statementClass());
            card.put(choice.pattern().sourceField(), sources.get(edge.source().qid()));
            card.put(choice.pattern().targetField(), targets.get(edge.target().qid()));
            card.put("Relation", choice.pattern().relation().relationId());
            statementCards.add(card);
        }
        GeneratedClassModel targetClass =
                model.findClass(choice.pattern().targetNodeClass());
        java.util.Set<String> unexpanded = frontierQids(choice.pattern(),
                targets.keySet(), selected.keySet(),
                targetClass == null ? List.of() : targetClass.seedQids());
        List<Viewable> frontier = targets.entrySet().stream()
                .filter(entry -> unexpanded.contains(entry.getKey()))
                .map(Map.Entry::getValue).map(Viewable.class::cast).toList();
        setTab(0, "Selected nodes", new ArrayList<>(selected.values()));
        setTab(1, "Sources", new ArrayList<>(sources.values()));
        setTab(2, "Statements", statementCards);
        setTab(3, "Frontier", frontier);
        diagram.counts(selected.size(), sources.size(), statementCards.size(), frontier.size());
        status.setText(statementCards.size() + " statement sample(s), "
                + frontier.size() + " frontier node(s).");
    }

    /**
     * The reached nodes the model has not already expanded. A node configured as an
     * expansion seed is not frontier even when this preview did not ask about it:
     * only the previewed subset is selected, and the box holds at most three.
     *
     * <p>The rule itself belongs to the graph layer and is shared with generation, so
     * this preview and a generated snapshot cannot mean different things by
     * "frontier" — only look different distances.</p>
     */
    static java.util.Set<String> frontierQids(
            GraphExpansionPattern pattern,
            java.util.Collection<String> reached,
            java.util.Collection<String> previewed,
            java.util.Collection<String> configuredSeeds) {
        List<datasource.EntityRef> expanded = WikidataGraphDiscoveryState.nodes(
                java.util.stream.Stream.concat(previewed.stream(),
                        configuredSeeds.stream()).toList());
        List<GraphExpansionCoverage> coverage = GraphExpansionCoverage.of(pattern,
                GraphExpansionCoverage.Direction.INCOMING, expanded,
                WikidataGraphDiscoveryState.nodes(reached));
        return new GraphDiscoveryState(List.of(pattern), coverage)
                .frontier(pattern.id()).stream().map(item -> item.node().id())
                .collect(java.util.stream.Collectors
                        .toCollection(java.util.LinkedHashSet::new));
    }

    private static Map<String, DynamicViewable> views(
            List<GraphPatternSampleQuery.Node> nodes, String type) {
        Map<String, DynamicViewable> out = new LinkedHashMap<>();
        for (GraphPatternSampleQuery.Node node : nodes) {
            DynamicViewable view = new DynamicViewable(node.qid(), node.label());
            view.type(type); view.put("QID", node.qid());
            out.putIfAbsent(node.qid(), view);
        }
        return out;
    }

    private void setTab(int index, String title, List<? extends Viewable> cards) {
        results.setTitleAt(index, title + " (" + cards.size() + ")");
        results.setComponentAt(index, cards.isEmpty() ? empty("No sampled values.")
                : SearchableView.builder(cards).sample(cards.getFirst())
                        .mode(RenderingMode.CARD).columns(2).collapsible(false).build());
    }

    private void updateEnabled() {
        preview.setEnabled(runner != null && patterns.getSelectedItem() != null);
    }

    private static JComponent empty(String text) { return new JLabel("  " + text); }
    private static String message(Throwable error) {
        return error == null || error.getMessage() == null
                ? "Unknown error" : error.getMessage();
    }

    /** Compact explanatory diagram; data remains accessible in the card tabs below. */
    private static final class PatternDiagram extends JComponent {
        // Text follows the look and feel, because a foreground that ignores it is
        // unreadable on a dark one. The accent stays literal, as it is in the card
        // renderers this diagram sits above, and is applied as a TINT so it
        // composites over whatever background is actually beneath it.
        private static final Color ACCENT = new Color(30, 110, 210);
        private static Color ui(String key, Color fallback) {
            Color color = UIManager.getColor(key);
            return color == null ? fallback : color;
        }
        private static Color text() { return ui("Label.foreground", Color.DARK_GRAY); }
        private static Color muted() {
            return ui("Label.disabledForeground", Color.GRAY);
        }
        private static Color tint() {
            return new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 28);
        }

        private GraphExpansionPattern pattern;
        private int selected, sources, statements, frontier;
        PatternDiagram() { setPreferredSize(new Dimension(760, 125)); }
        void pattern(GraphExpansionPattern value) {
            pattern = value; selected = sources = statements = frontier = 0; repaint();
        }
        void counts(int a, int b, int c, int d) {
            selected = a; sources = b; statements = c; frontier = d; repaint();
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (pattern == null) {
                    g.setColor(muted());
                    g.drawString("No graph pattern selected.", 16, 32);
                    return;
                }
                int y = 22, h = 54, gap = 34;
                int w = Math.max(120, (getWidth() - 5 * gap) / 4);
                int x1 = gap, x2 = x1 + w + gap, x3 = x2 + w + gap, x4 = x3 + w + gap;
                box(g, x1, y, w, h, pattern.targetNodeClass(), "selected · " + selected);
                box(g, x2, y, w, h, pattern.sourceNodeClass(), "discovered · " + sources);
                box(g, x3, y, w, h, pattern.statementClass(),
                        pattern.relation().relationId() + " · " + statements);
                box(g, x4, y, w, h, pattern.targetNodeClass(), "frontier · " + frontier);
                arrow(g, x1 + w, y + h / 2, x2, y + h / 2, "reverse");
                arrow(g, x2 + w, y + h / 2, x3, y + h / 2, "statements");
                arrow(g, x3 + w, y + h / 2, x4, y + h / 2, "values");
            } finally { g.dispose(); }
        }
        private static void box(Graphics2D g, int x, int y, int w, int h,
                                String title, String detail) {
            g.setColor(tint()); g.fillRoundRect(x, y, w, h, 12, 12);
            g.setColor(ACCENT); g.drawRoundRect(x, y, w, h, 12, 12);
            g.setColor(text()); g.setFont(g.getFont().deriveFont(Font.BOLD));
            g.drawString(title, x + 9, y + 21);
            g.setColor(muted());
            g.setFont(g.getFont().deriveFont(Font.PLAIN));
            g.drawString(detail, x + 9, y + 41);
        }
        private static void arrow(Graphics2D g, int x1, int y1, int x2, int y2,
                                  String label) {
            g.setColor(muted()); g.drawLine(x1 + 3, y1, x2 - 5, y2);
            g.drawLine(x2 - 12, y2 - 5, x2 - 5, y2); g.drawLine(x2 - 12, y2 + 5, x2 - 5, y2);
            Font old = g.getFont(); g.setFont(old.deriveFont(10f));
            g.drawString(label, x1 + 7, y1 - 6); g.setFont(old);
        }
    }
}
