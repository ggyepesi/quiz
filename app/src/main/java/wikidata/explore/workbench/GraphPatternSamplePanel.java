package wikidata.explore.workbench;

import datasource.graph.GraphDiscoveryState;
import datasource.graph.GraphExpansionCoverage;
import datasource.graph.GraphExpansionPattern;
import objectview.Viewable;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import quiz.transform.DynamicViewable;
import wikidata.WikidataIds;
import wikidata.explore.generation.WikidataGraphDiscoveryState;
import wikidata.explore.generation.WikidataGraphExpansionPlan;
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
    private final GraphPatternDiagram diagram = new GraphPatternDiagram();
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
        var plan = WikidataGraphExpansionPlan.compile(model);
        diagram.traversalSteps(plan.traversalSteps());
        for (GraphExpansionPattern pattern : plan.patterns()) {
            patterns.addItem(new Choice(pattern.statementClass(), pattern));
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
            status.setText(WikidataGraphExpansionPlan.compile(model).traversalSteps().isEmpty()
                    ? "No enabled, structurally complete graph pattern."
                    : "Field traversal configured; bounded-wave preview is the next stage.");
            diagram.pattern(null, GraphPatternDiagram.Details.counts(0, 0, 0, 0));
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
        diagram.pattern(choice.pattern(), GraphPatternDiagram.Details.counts(0, 0, 0, 0));
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
        diagram.details(GraphPatternDiagram.Details.counts(
                selected.size(), sources.size(), statementCards.size(), frontier.size()));
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
                expanded,
                WikidataGraphDiscoveryState.nodes(reached));
        return new GraphDiscoveryState(List.of(pattern), coverage)
                .frontier(pattern).stream()
                .map(item -> item.node().id())
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

}
