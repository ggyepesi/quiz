package domain.workbench.modelbuilder.ui;

import domain.datasource.graph.RelationRoute;
import objectview.render.RenderingMode;
import objectview.view.SearchableView;
import domain.analysis.RelationRouteDiscovery;
import wikidata.explore.query.logical.DiscoverRelationRoutesQuery;
import domain.workbench.execution.ui.SwingQueryRunner;
import wikidata.ui.WikidataLinks;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Read-only, bounded route discovery plus one explicit hand-off to a field editor. */
final class RelationRouteDiscoveryPanel extends JPanel {
    private final JTextArea examples = new JTextArea(4, 46);
    private final JSpinner maximumSteps =
            new JSpinner(new SpinnerNumberModel(3, 1, 6, 1));
    private final JSpinner maximumQids =
            new JSpinner(new SpinnerNumberModel(250, 1, 5_000, 25));
    private final JButton discover = new JButton("Discover routes");
    private final JButton cancel = new JButton("Cancel");
    private final JButton addRoute = new JButton("Add selected route to field");
    private final JLabel target = new JLabel("No field selected");
    private final JLabel status = new JLabel("Enter explicit source → expected target examples.");
    private final JPanel results = new JPanel(new BorderLayout());
    private RelationRouteDiscovery.Candidate selected;
    private Consumer<RelationRoute> onAddRoute = ignored -> { };
    private Supplier<String> targetName = () -> "";
    private Supplier<String> addUnavailable = () -> "Select an entity-list field first.";
    private objectview.search.SearchPanel.ConfigState resultConfig;
    private boolean wired;

    RelationRouteDiscoveryPanel() {
        super(new BorderLayout(6, 6));
        examples.setLineWrap(false);
        examples.setToolTipText("One line per source: Qsource → Qtarget [Qtarget …]");

        JPanel inputs = new JPanel(new GridBagLayout());
        inputs.setBorder(BorderFactory.createEmptyBorder(8, 8, 2, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        inputs.add(new JLabel("Examples:"), c);
        c.gridx = 1; c.weightx = 1;
        inputs.add(new JScrollPane(examples), c);
        c.gridy++; c.gridx = 0; c.weightx = 0;
        inputs.add(new JLabel("Maximum typed steps:"), c);
        c.gridx = 1; c.weightx = 1;
        inputs.add(maximumSteps, c);
        c.gridy++; c.gridx = 0; c.weightx = 0;
        inputs.add(new JLabel("Maximum inspected QIDs:"), c);
        c.gridx = 1; c.weightx = 1;
        inputs.add(maximumQids, c);
        c.gridy++; c.gridx = 0; c.gridwidth = 2;
        inputs.add(new JLabel("<html>The search reads direct properties and "
                + "statement qualifiers from only this bounded frontier. Incoming "
                + "reads are capped per QID; broad or timed-out QIDs are skipped. "
                + "It does not "
                + "change the model. A route is stored only by <b>Add selected route "
                + "to field</b>.</html>"), c);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actions.add(discover);
        actions.add(cancel);
        actions.add(new JLabel("Field:"));
        actions.add(target);
        actions.add(addRoute);
        actions.add(status);
        c.gridy++;
        inputs.add(actions, c);
        add(inputs, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);
        cancel.setEnabled(false);
        select(null);
        showResults(List.of());

        addRoute.addActionListener(event -> {
            if (selected == null) return;
            String unavailable = addUnavailable.get();
            if (unavailable != null && !unavailable.isBlank()) {
                status.setText(unavailable);
                return;
            }
            try {
                onAddRoute.accept(selected.route());
                status.setText("Added \"" + selected.route().label()
                        + "\" to " + targetName.get()
                        + ". Save model/domain to keep it.");
                refreshTarget();
            } catch (IllegalArgumentException refused) {
                status.setText("Route not added: " + message(refused));
            }
        });
    }

    void setQueryRunner(SwingQueryRunner runner) {
        if (wired || runner == null) return;
        wired = true;
        runner.registerCancelButton(cancel);
        runner.wireButton(discover, this::accept, this::query,
                failure -> status.setText("Route discovery failed: " + message(failure)));
    }

    void target(Supplier<String> name, Supplier<String> unavailable,
            Consumer<RelationRoute> add) {
        targetName = name == null ? () -> "" : name;
        addUnavailable = unavailable == null ? () -> "" : unavailable;
        onAddRoute = add == null ? ignored -> { } : add;
        refreshTarget();
    }

    void refreshTarget() {
        String name = targetName.get();
        target.setText(name == null || name.isBlank() ? "No field selected" : name);
        select(selected);
    }

    private DiscoverRelationRoutesQuery query() {
        List<RelationRouteDiscovery.Example> parsed =
                DiscoverRelationRoutesQuery.parseExamples(examples.getText());
        int steps = ((Number) maximumSteps.getValue()).intValue();
        int qids = ((Number) maximumQids.getValue()).intValue();
        status.setText("Will inspect up to " + qids + " QIDs through at most "
                + steps + " typed steps…");
        select(null);
        return new DiscoverRelationRoutesQuery(parsed, steps, qids);
    }

    private void accept(RelationRouteDiscovery.Result result) {
        List<RelationRouteDiscovery.Candidate> candidates = result == null
                ? List.of() : result.candidates();
        showResults(candidates);
        status.setText(candidates.size() + " route(s); "
                + (result == null ? 0 : result.entitiesInspected()) + " QID(s) inspected"
                + (result != null && result.discoveryLimitReached()
                        ? " — bounded search skipped broad, timed-out, or excess branches"
                        : ""));
    }

    private void showResults(List<RelationRouteDiscovery.Candidate> candidates) {
        List<RelationRouteCandidateViewable> rows = candidates.stream()
                .map(RelationRouteCandidateViewable::new).toList();
        SearchableView view = SearchableView.builder(rows)
                .type(RelationRouteCandidateViewable.class)
                .mode(RenderingMode.TABLE)
                .valueLinker(WikidataLinks.valueLinker())
                .emptyMessage("No matching routes yet.")
                .configState(resultConfig)
                .configListener(state -> resultConfig = state)
                .selectionSetListener(values -> select(values.size() == 1
                        && values.iterator().next() instanceof RelationRouteCandidateViewable row
                                ? row.candidate() : null))
                .build();
        results.removeAll();
        results.add(view, BorderLayout.CENTER);
        results.revalidate();
        results.repaint();
        select(null);
    }

    private void select(RelationRouteDiscovery.Candidate value) {
        selected = value;
        String unavailable = addUnavailable.get();
        boolean canAdd = value != null && (unavailable == null || unavailable.isBlank());
        addRoute.setEnabled(canAdd);
        addRoute.setToolTipText(canAdd
                ? "Append this route to the selected field's bounded relation routes."
                : unavailable == null || unavailable.isBlank()
                        ? "Select one discovered route." : unavailable);
    }

    private static String message(Throwable failure) {
        if (failure == null) return "Unknown failure";
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
