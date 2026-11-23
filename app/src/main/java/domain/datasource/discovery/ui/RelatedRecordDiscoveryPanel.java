package domain.datasource.discovery.ui;

import domain.datasource.EntityRef;
import domain.datasource.api.DatasourceProvider;
import domain.datasource.api.discovery.RelatedRecordDiscoveryOperation;
import domain.datasource.api.discovery.RelatedRecordDiscoveryRequest;
import domain.datasource.api.discovery.RelatedRecordDiscoveryResult;
import domain.datasource.api.discovery.RelatedRecordSeed;
import objectview.demo.MultiView;
import objectview.utils.swing.WrapLayout;
import domain.transform.ui.BoundedInstanceSelection;
import domain.transform.ui.TransformController;
import domain.runtime.WikidataDynamicObject;
import domain.workbench.execution.ui.SwingQueryRunner;
import domain.workbench.execution.ui.SwingQuerySession;
import work.QueryContext;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Generic Discovery-workbench tab for a configured related-record operation. */
final class RelatedRecordDiscoveryPanel extends JPanel implements AutoCloseable {
    private final TransformController domain;
    private final BoundedInstanceSelection selection;
    private final DatasourceProvider provider;
    private final RelatedRecordDiscoveryOperation operation;
    private final SwingQuerySession queries = new SwingQuerySession(new QueryContext());
    private final SwingQueryRunner runner = queries.runner();

    private final JButton fetch = new JButton("Fetch related records");
    private final JButton cancel = new JButton("Cancel");
    private final JButton logs = new JButton("Query logs");
    private final JLabel availability = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JLabel resultStatus = new JLabel("Select exact instances, then fetch records.");
    private final JTabbedPane resultTabs =
            new domain.workbench.ui.WorkbenchResultViews();
    private DiscoveryResultListener resultListener = DiscoveryResultListener.NONE;
    private boolean closed;

    RelatedRecordDiscoveryPanel(TransformController domain,
            BoundedInstanceSelection selection, DatasourceProvider provider,
            RelatedRecordDiscoveryOperation operation) {
        super(new BorderLayout(6, 6));
        this.domain = java.util.Objects.requireNonNull(domain);
        this.selection = java.util.Objects.requireNonNull(selection);
        this.provider = java.util.Objects.requireNonNull(provider);
        this.operation = java.util.Objects.requireNonNull(operation);
        selection.addHost(new BoundedInstanceSelection.Host() {
            @Override public void selectionChanged(String reason) {
                invalidateResults(reason);
            }
            @Override public void updateActions() {
                RelatedRecordDiscoveryPanel.this.updateActions();
            }
        });
        build();
        wire();
        emptyResults();
        updateActions();
    }

    String title() { return provider.displayName() + " — " + operation.displayName(); }
    String sourceId() { return "configured." + provider.id(); }
    String sourceTitle() { return provider.displayName(); }
    String actionId() { return sourceId() + "." + operation.id(); }
    String actionTitle() { return operation.displayName(); }

    private void build() {
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        controls.add(fetch);
        controls.add(cancel);
        controls.add(logs);
        JPanel north = new JPanel(new BorderLayout(4, 3));
        north.add(controls, BorderLayout.NORTH);
        JPanel messages = new JPanel(new GridLayout(0, 1, 0, 2));
        messages.add(availability);
        messages.add(status);
        north.add(messages, BorderLayout.SOUTH);
        JPanel results = new JPanel(new BorderLayout(4, 4));
        results.add(resultStatus, BorderLayout.NORTH);
        results.add(resultTabs, BorderLayout.CENTER);
        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        results.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(north, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);
    }

    private void wire() {
        fetch.addActionListener(event -> fetchSelected());
        logs.addActionListener(event -> queries.showLogs(this));
        runner.registerRunButton(fetch);
        runner.registerCancelButton(cancel);
        runner.onRunningChanged(running -> {
            selection.setRunning(this, running);
            updateActions();
        });
    }

    private List<RelatedRecordSeed> seeds() {
        return selection.selected().stream().map(value -> new RelatedRecordSeed(
                value.identityTypeName() + "\u0000" + value.getIdentifier(),
                EntityRef.wikidata(value.qid()), value.getDisplayName())).toList();
    }

    private String description() {
        int count = selection.size();
        return "Search " + provider.displayName() + " for exactly " + count
                + " selected instance(s), at most " + operation.maximumRecordsPerSeed()
                + " search result(s) per instance and "
                + (count * operation.maximumRecordsPerSeed())
                + " detail request(s) before duplicate record IDs are merged. Retain only "
                + "records whose configured evidence names the selected QID. The project "
                + "is not changed.";
    }

    private void fetchSelected() {
        List<RelatedRecordSeed> exact = seeds();
        if (exact.isEmpty()) return;
        status.setText(description());
        var query = operation.discover(new RelatedRecordDiscoveryRequest(exact));
        runner.run(query,
                result -> SwingUtilities.invokeLater(() -> showResult(result)),
                failure -> SwingUtilities.invokeLater(() -> {
                    String message = failure == null || failure.getMessage() == null
                            ? title() + " failed." : failure.getMessage();
                    status.setText(title() + " failed: " + message);
                    JOptionPane.showMessageDialog(this, message,
                            title() + " failed", JOptionPane.ERROR_MESSAGE);
                }));
    }

    private void showResult(RelatedRecordDiscoveryResult result) {
        resultTabs.removeAll();
        Map<String, WikidataDynamicObject> selectedByKey = new LinkedHashMap<>();
        for (WikidataDynamicObject value : selection.selected()) {
            selectedByKey.put(value.identityTypeName() + "\u0000" + value.getIdentifier(),
                    value);
        }
        List<DiscoveredRecordView> records = result.records().stream().map(record ->
                new DiscoveredRecordView(record, operation.recordTypeName(),
                        operation.recordFields(), record.matchedSeedKeys().stream()
                                .map(selectedByKey::get).filter(java.util.Objects::nonNull)
                                .toList())).toList();

        MultiView connected = new MultiView();
        connected.context().setFieldSchemaResolver(value ->
                value instanceof WikidataDynamicObject dynamic
                        ? domain.renderedFieldSchema(dynamic, dynamic.typeName())
                        : value instanceof DiscoveredRecordView discovered
                        ? discovered.dynamicFieldSchema() : null);
        connected.context().setTypeSchemaResolver(domain::fieldSchema);
        connected.context().setValueLinker(value -> {
            if (value instanceof CharSequence text) {
                String candidate = text.toString().trim();
                if (candidate.startsWith("https://") || candidate.startsWith("http://")) {
                    return candidate;
                }
            }
            return wikidata.ui.WikidataLinks.valueLinker().apply(value);
        });
        String className = selection.className();
        connected.addSection(className, WikidataDynamicObject.class, selection.selected(),
                domain.configSample(className), domain.structuralFields(className),
                domain.fieldTypes(className));
        DiscoveredRecordView.addSection(connected, operation.recordTypeName(), records);
        connected.build(1);
        resultTabs.addTab("Connected objects — " + records.size(), connected);

        LinkedHashSet<String> matched = new LinkedHashSet<>();
        result.records().forEach(record -> matched.addAll(record.matchedSeedKeys()));
        List<WikidataDynamicObject> without = selectedByKey.entrySet().stream()
                .filter(entry -> !matched.contains(entry.getKey()))
                .map(Map.Entry::getValue).toList();
        if (!without.isEmpty()) {
            resultTabs.addTab("No exact related record — " + without.size(),
                    selection.instanceView(without, className, ignored -> { }));
        }
        resultStatus.setText(records.size() + " exact " + operation.recordTypeName()
                + " record(s) from " + result.searchedCandidates()
                + " distinct bounded candidate ID(s) for " + result.seedCount()
                + " selected instance(s)."
                + (result.failedWork().isEmpty() ? "" : " " + result.failedWork().size()
                        + " request(s) failed; the result is partial. Open Query logs for "
                        + "their exact names.")
                + " Results are read-only.");
        status.setText(title() + " completed"
                + (result.partial() ? " with unavailable records." : "."));
        resultListener.published(actionId(), resultTabs, 0);
        updateActions();
    }

    private void invalidateResults(String reason) {
        emptyResults();
        resultStatus.setText(reason);
        resultListener.invalidated(actionId());
        updateActions();
    }

    void setResultListener(DiscoveryResultListener listener) {
        resultListener = listener == null ? DiscoveryResultListener.NONE : listener;
    }

    private void emptyResults() {
        resultTabs.removeAll();
        resultTabs.addTab("Connected objects", new JPanel());
    }

    private void updateActions() {
        boolean running = runner.isRunning();
        int count = selection.size();
        boolean bounded = count <= operation.maximumSeeds();
        fetch.setEnabled(!running && count > 0 && bounded);
        fetch.setText(running ? "Fetching " + operation.recordTypeName() + " records…"
                : "Fetch " + operation.recordTypeName() + " records for " + count
                        + " selected instance" + (count == 1 ? "" : "s"));
        fetch.setToolTipText(count == 0 ? "Select at least one instance."
                : !bounded ? "One run accepts at most " + operation.maximumSeeds()
                        + " selected instances; remove "
                        + (count - operation.maximumSeeds()) + "."
                        : description());
        availability.setText(availability(running, count));
    }

    private String availability(boolean running, int count) {
        if (running) {
            return "Fetching " + operation.recordTypeName() + " records from "
                    + provider.displayName() + " for exactly " + count
                    + " selected instance(s).";
        }
        if (count == 0) {
            return "Cannot fetch " + operation.recordTypeName()
                    + " records: no instances are selected. Select between 1 and "
                    + operation.maximumSeeds() + "; no request has started.";
        }
        if (count > operation.maximumSeeds()) {
            return "Cannot fetch " + operation.recordTypeName() + " records from "
                    + provider.displayName() + ": " + count
                    + " instances are selected, but one run accepts at most "
                    + operation.maximumSeeds() + ". Remove "
                    + (count - operation.maximumSeeds())
                    + " selected instance(s); no request has started.";
        }
        return "Ready to fetch " + operation.recordTypeName() + " records from "
                + provider.displayName() + " for exactly " + count
                + " selected instance(s), retaining at most "
                + operation.maximumRecordsPerSeed()
                + " candidate record(s) per instance. The project will not be changed.";
    }

    boolean requestStopRun(Component owner) {
        return !runner.isRunning()
                || process.swing.SwingCancellationConfirmation.requestForVisibleRun(
                        owner, runner::cancel);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        runner.cancel();
    }
}
