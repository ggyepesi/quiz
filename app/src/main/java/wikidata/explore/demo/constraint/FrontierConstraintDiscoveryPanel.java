package wikidata.explore.demo.constraint;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import wikidata.WikidataSparqlClient;
import wikidata.explore.demo.constraint.FrontierConstraintDiscovery.Constraint;
import wikidata.explore.demo.constraint.FrontierConstraintDiscovery.Edge;
import wikidata.explore.workbench.CachedPropertyViewablePanel;
import wikidata.explore.workbench.WikidataPropertyViewable;
import wikidata.ui.WikidataLinks;

import javax.swing.*;
import java.awt.*;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Experimental one-step-at-a-time graph discovery with sampled constraint suggestions. */
public final class FrontierConstraintDiscoveryPanel extends JPanel {
    private static final String USER_AGENT =
            "QuizProject/1.0 (https://github.com/ggyepesi/quiz)";

    private final JTextField start = new JTextField("Q6412254", 11);
    private final JTextField incoming = new JTextField("P39", 7);
    private final JTextField outgoing = new JTextField("P39", 7);
    private final JSpinner previewLimit = new JSpinner(new SpinnerNumberModel(60, 1, 500, 10));
    private final JSpinner suggestionLimit = new JSpinner(new SpinnerNumberModel(150, 1, 500, 25));
    private final JButton preview = new JButton("Preview frontier step");
    private final JButton discover = new JButton("Discover constraints");
    private final JButton addConstraint = new JButton("Use selected constraint");
    private final JButton clearConstraints = new JButton("Clear constraints");
    private final JButton advance = new JButton("Use selected as next frontier");
    private final JButton useIncoming = new JButton("Use property as incoming edge");
    private final JButton useOutgoing = new JButton("Use property as outgoing edge");
    private final JLabel frontierLabel = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JPanel candidatesHolder = new JPanel(new BorderLayout());
    private final JPanel edgesHolder = new JPanel(new BorderLayout());
    private final JPanel suggestionsHolder = new JPanel(new BorderLayout());
    private final JTextArea activeConstraints = new JTextArea(5, 40);
    private final CachedPropertyViewablePanel properties = new CachedPropertyViewablePanel();

    private List<String> frontier = List.of(start.getText());
    private final List<Constraint> constraints = new ArrayList<>();
    private FrontierConstraintDiscovery.Preview lastPreview;
    private Constraint selectedConstraint;
    private List<CandidateView> selectedCandidates = List.of();
    private WikidataPropertyViewable selectedProperty;
    private SwingWorker<?, ?> worker;

    public FrontierConstraintDiscoveryPanel() {
        super(new BorderLayout(7, 7));
        buildUi();
        wireActions();
        showEmptyResults();
        refreshState();
    }

    private void buildUi() {
        JPanel pattern = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        pattern.add(new JLabel("Start QID:")); pattern.add(start);
        pattern.add(new JLabel("incoming edge:")); pattern.add(incoming);
        pattern.add(new JLabel("outgoing edge:")); pattern.add(outgoing);
        pattern.add(new JLabel("preview limit:")); pattern.add(previewLimit);
        pattern.add(new JLabel("suggestions:")); pattern.add(suggestionLimit);
        pattern.add(preview); pattern.add(discover);

        JPanel state = new JPanel(new BorderLayout(6, 2));
        state.add(frontierLabel, BorderLayout.CENTER);
        state.add(status, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout());
        north.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));
        north.add(objectview.utils.swing.ScrollPaneUtils.horizontalOnly(pattern),
                BorderLayout.NORTH);
        north.add(state, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        JTabbedPane results = new JTabbedPane();
        results.addTab("Candidate frontier", candidatesHolder);
        results.addTab("Witness edges", edgesHolder);
        results.addTab("Constraint suggestions", suggestionsHolder);
        activeConstraints.setEditable(false);
        activeConstraints.setLineWrap(true);
        activeConstraints.setWrapStyleWord(true);
        results.addTab("Active constraints", new JScrollPane(activeConstraints));

        JPanel resultActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        resultActions.add(advance);
        resultActions.add(addConstraint);
        resultActions.add(clearConstraints);
        JPanel resultSide = new JPanel(new BorderLayout());
        resultSide.add(results, BorderLayout.CENTER);
        resultSide.add(resultActions, BorderLayout.SOUTH);

        JPanel propertyActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        propertyActions.add(useIncoming); propertyActions.add(useOutgoing);
        JPanel propertySide = new JPanel(new BorderLayout());
        propertySide.add(new JLabel("Downloaded properties"), BorderLayout.NORTH);
        propertySide.add(properties, BorderLayout.CENTER);
        propertySide.add(propertyActions, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                resultSide, propertySide);
        split.setResizeWeight(0.68);
        split.setDividerLocation(0.68);
        add(split, BorderLayout.CENTER);

        JLabel help = new JLabel("<html>Selecting inspects. Preview queries one bounded "
                + "frontier step. Discover profiles that sample. Explicit buttons choose a "
                + "constraint or advance the frontier; nothing computes a closure.</html>");
        help.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
        add(help, BorderLayout.SOUTH);
    }

    private void wireActions() {
        preview.addActionListener(event -> previewStep());
        discover.addActionListener(event -> discoverConstraints());
        addConstraint.addActionListener(event -> useConstraint());
        clearConstraints.addActionListener(event -> {
            constraints.clear(); lastPreview = null; refreshState();
        });
        advance.addActionListener(event -> advanceFrontier());
        properties.onSelectionChanged(selected -> {
            selectedProperty = selected.size() == 1 ? selected.getFirst() : null;
            refreshState();
        });
        useIncoming.addActionListener(event -> {
            if (selectedProperty != null) incoming.setText(selectedProperty.pid());
        });
        useOutgoing.addActionListener(event -> {
            if (selectedProperty != null) outgoing.setText(selectedProperty.pid());
        });
    }

    private void previewStep() {
        frontier = parseQids(start.getText(), frontier);
        SwingWorker<FrontierConstraintDiscovery.Preview, Void> task = new SwingWorker<>() {
            @Override protected FrontierConstraintDiscovery.Preview doInBackground()
                    throws Exception {
                try (WikidataSparqlClient client = client()) {
                    return FrontierConstraintDiscovery.preview(client, frontier,
                            incoming.getText().trim(), outgoing.getText().trim(),
                            constraints, (Integer) previewLimit.getValue());
                }
            }
            @Override protected void done() { finishPreview(this); }
        };
        worker = task;
        setRunning(true, "Previewing one frontier step…");
        task.execute();
    }

    private void finishPreview(SwingWorker<FrontierConstraintDiscovery.Preview, Void> task) {
        try {
            lastPreview = task.get();
            showPreview(lastPreview);
            status.setText(lastPreview.edges().size() + " witness edge(s)"
                    + (lastPreview.limitReached() ? " — limit reached" : ""));
        } catch (Exception failure) {
            showFailure(failure);
        } finally {
            setRunning(false, null);
        }
    }

    private void discoverConstraints() {
        if (lastPreview == null || lastPreview.edges().isEmpty()) return;
        SwingWorker<List<Constraint>, Void> task = new SwingWorker<>() {
            @Override protected List<Constraint> doInBackground() throws Exception {
                try (WikidataSparqlClient client = client()) {
                    return FrontierConstraintDiscovery.discover(client, lastPreview,
                            outgoing.getText().trim(), (Integer) suggestionLimit.getValue());
                }
            }
            @Override protected void done() {
                try {
                    showSuggestions(get());
                    status.setText("Constraint suggestions describe the current sample.");
                } catch (Exception failure) {
                    showFailure(failure);
                } finally {
                    setRunning(false, null);
                }
            }
        };
        worker = task;
        setRunning(true, "Profiling the preview sample…");
        task.execute();
    }

    private void useConstraint() {
        if (selectedConstraint == null || constraints.contains(selectedConstraint)) return;
        constraints.add(selectedConstraint);
        lastPreview = null;
        refreshState();
        status.setText("Constraint added; preview the same frontier again.");
    }

    private void advanceFrontier() {
        if (selectedCandidates.isEmpty()) return;
        frontier = selectedCandidates.stream().map(CandidateView::qid).distinct().toList();
        start.setText(String.join(" ", frontier));
        constraints.clear();
        lastPreview = null;
        showEmptyResults();
        refreshState();
        status.setText("Next frontier selected; preview when ready.");
    }

    private void showPreview(FrontierConstraintDiscovery.Preview result) {
        LinkedHashMap<String, CandidateView> candidates = new LinkedHashMap<>();
        for (Edge edge : result.edges()) {
            candidates.putIfAbsent(edge.candidateQid(),
                    new CandidateView(edge.candidateQid(), edge.candidateLabel()));
        }
        SearchableView candidateView = SearchableView.builder(candidates.values())
                .type(CandidateView.class).mode(RenderingMode.TABLE)
                .valueLinker(WikidataLinks.valueLinker())
                .selectionSetListener(selected -> {
                    selectedCandidates = selected.stream()
                            .filter(CandidateView.class::isInstance)
                            .map(CandidateView.class::cast).toList();
                    refreshState();
                }).build();
        replace(candidatesHolder, candidateView);
        List<EdgeView> edges = result.edges().stream().map(EdgeView::new).toList();
        replace(edgesHolder, SearchableView.builder(edges)
                .type(EdgeView.class).mode(RenderingMode.TABLE)
                .valueLinker(WikidataLinks.valueLinker()).build());
        replace(suggestionsHolder, message("Discover constraints from this bounded sample."));
        selectedCandidates = List.of();
        selectedConstraint = null;
        refreshState();
    }

    private void showSuggestions(List<Constraint> suggestions) {
        List<ConstraintView> views = suggestions.stream().map(ConstraintView::new).toList();
        replace(suggestionsHolder, SearchableView.builder(views)
                .type(ConstraintView.class).mode(RenderingMode.TABLE)
                .valueLinker(WikidataLinks.valueLinker())
                .selectionListener(selected -> {
                    selectedConstraint = selected instanceof ConstraintView view
                            ? view.constraint : null;
                    refreshState();
                }).build());
    }

    private void showEmptyResults() {
        replace(candidatesHolder, message("Preview one frontier step."));
        replace(edgesHolder, message("Witness edges retain the bridge entity."));
        replace(suggestionsHolder, message("Preview, then discover constraints."));
        selectedCandidates = List.of(); selectedConstraint = null;
    }

    private void refreshState() {
        frontierLabel.setText("Frontier: " + String.join(", ", frontier));
        activeConstraints.setText(constraints.isEmpty() ? "No active constraints."
                : constraints.stream().map(FrontierConstraintDiscoveryPanel::describe)
                .reduce((left, right) -> left + "\n" + right).orElse(""));
        boolean idle = worker == null;
        preview.setEnabled(idle);
        discover.setEnabled(idle && lastPreview != null && !lastPreview.edges().isEmpty());
        addConstraint.setEnabled(idle && selectedConstraint != null);
        advance.setEnabled(idle && !selectedCandidates.isEmpty());
        clearConstraints.setEnabled(idle && !constraints.isEmpty());
        useIncoming.setEnabled(idle && selectedProperty != null);
        useOutgoing.setEnabled(idle && selectedProperty != null);
    }

    private void setRunning(boolean running, String message) {
        if (running && message != null) status.setText(message);
        if (!running) worker = null;
        refreshState();
    }

    private void showFailure(Exception failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        status.setText("Failed: " + (cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    private static WikidataSparqlClient client() {
        WikidataSparqlClient client = new WikidataSparqlClient(USER_AGENT, 1,
                WikidataSparqlClient.WIKIDATA_ENDPOINT, HttpClient.Version.HTTP_1_1);
        client.minRequestSpacingMillis(250);
        return client;
    }

    private static String describe(Constraint value) {
        return value.scope().label() + ": " + value.propertyPid() + " → "
                + value.valueLabel() + " (" + value.valueQid() + ")";
    }

    private static List<String> parseQids(String text, List<String> fallback) {
        List<String> result = java.util.Arrays.stream(
                        (text == null ? "" : text).split("[,;\\s]+"))
                .filter(wikidata.WikidataIds::isQid).distinct().toList();
        return result.isEmpty() ? fallback : result;
    }

    private static JLabel message(String text) { return new JLabel("   " + text); }

    private static void replace(JPanel holder, Component content) {
        holder.removeAll(); holder.add(content, BorderLayout.CENTER);
        holder.revalidate(); holder.repaint();
    }

    public CachedPropertyViewablePanel propertyPanel() { return properties; }
    public String frontierText() { return frontierLabel.getText(); }
    public String helpText() {
        return java.util.Arrays.stream(getComponents())
                .filter(JLabel.class::isInstance).map(JLabel.class::cast)
                .map(JLabel::getText).findFirst().orElse("");
    }

    public static final class CandidateView extends ViewableAdapter {
        private final String qid;
        @DisplayField private final String name;
        CandidateView(String qid, String name) { this.qid = qid; this.name = name; }
        String qid() { return qid; }
        @Override public String getIdentifier() { return qid; }
        @Override public String getDisplayName() { return name; }
    }

    public static final class EdgeView extends ViewableAdapter {
        @DisplayField private final String candidate;
        private final String candidateQid;
        private final String via;
        private final String viaQid;
        private final String from;
        private final String fromQid;
        EdgeView(Edge edge) {
            candidate = edge.candidateLabel(); candidateQid = edge.candidateQid();
            via = edge.bridgeLabel(); viaQid = edge.bridgeQid();
            from = edge.sourceLabel(); fromQid = edge.sourceQid();
        }
        @Override public String getIdentifier() { return fromQid + ":" + viaQid + ":" + candidateQid; }
        @Override public String getDisplayName() { return candidate; }
    }

    public static final class ConstraintView extends ViewableAdapter {
        @objectview.annotations.Hidden private final Constraint constraint;
        @DisplayField private final String value;
        private final String valueQid;
        private final String appliesTo;
        private final String property;
        private final int sampleCount;
        ConstraintView(Constraint constraint) {
            this.constraint = constraint;
            value = constraint.valueLabel(); valueQid = constraint.valueQid();
            appliesTo = constraint.scope().label(); property = constraint.propertyPid();
            sampleCount = constraint.sampleCount();
        }
        @Override public String getIdentifier() {
            return constraint.scope() + ":" + property + ":" + valueQid;
        }
        @Override public String getDisplayName() { return value; }
    }
}
