package commons.discovery;

import domain.DomainModel;
import objectview.demo.MultiView;
import objectview.group.DefaultViewableGroup;
import objectview.group.ViewableGroup;
import objectview.render.GroupMembersView;
import objectview.view.SearchableView;
import objectview.utils.swing.WrapLayout;
import quiz.transform.ui.BoundedInstanceSelection;
import quiz.transform.ui.TransformController;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.SwingQuerySession;
import work.QueryContext;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only source workbench for Structured Data on Commons.  Selection is local and
 * explicit; inspecting a class never starts a request, and a completed discovery never
 * changes the loaded model or its saved snapshots.
 */
public final class CommonsDiscoveryPanel extends JPanel implements AutoCloseable {
    private static final int DEFAULT_SAMPLE = 12;

    private final TransformController domain;
    private final CommonsStructuredDataClient commons;
    private final SwingQuerySession queries = new SwingQuerySession(new QueryContext());
    private final SwingQueryRunner runner = queries.runner();

    private final JSpinner mediaLimit = new JSpinner(new SpinnerNumberModel(
            CommonsDiscoveryQuery.DEFAULT_MEDIA_PER_SUBJECT, 1, 100, 1));
    private final JButton fetch = new JButton("Fetch 0 selected entities");
    private final JButton cancel = new JButton("Cancel");
    private final JButton logs = new JButton("Query logs");
    private final JLabel selectionStatus = new JLabel(" ");
    private final JLabel resultStatus = new JLabel(
            "Select exact entities, then fetch their structured Commons depictions.");

    private final JTabbedPane resultTabs = new JTabbedPane();
    private final BoundedInstanceSelection selection;
    private boolean closed;

    public CommonsDiscoveryPanel(DomainModel model) {
        this(model, new CommonsStructuredDataClient());
    }

    CommonsDiscoveryPanel(DomainModel model, CommonsStructuredDataClient commons) {
        super(new BorderLayout(6, 6));
        this.domain = new TransformController(model, new quiz.transform.app.DomainSaver());
        this.commons = java.util.Objects.requireNonNull(commons);
        this.selection = new BoundedInstanceSelection(domain,
                new BoundedInstanceSelection.Host() {
                    @Override public void selectionChanged(String reason) {
                        invalidateResults(reason);
                    }
                    @Override public void status(String text) {
                        selectionStatus.setText(text);
                    }
                    @Override public void updateActions() {
                        CommonsDiscoveryPanel.this.updateActions();
                    }
                }, CommonsDiscoveryQuery.MAX_SUBJECTS, DEFAULT_SAMPLE, "entities",
                value -> WikidataIds.isQid(value.qid()), "have Wikidata QIDs");
        build();
        wire();
        if (!selection.populate(null)) {
            selectionStatus.setText("This project has no generated class instances "
                    + "with Wikidata QIDs. No Commons request can be made.");
            updateActions();
        }
    }

    private void build() {
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        selection.addControls(controls);
        controls.add(new JLabel("Maximum Commons files per selected QID:"));
        controls.add(mediaLimit);
        controls.add(fetch);
        controls.add(cancel);
        controls.add(logs);

        JPanel north = new JPanel(new BorderLayout(4, 3));
        north.add(controls, BorderLayout.NORTH);
        north.add(selectionStatus, BorderLayout.SOUTH);

        JPanel results = new JPanel(new BorderLayout(4, 4));
        results.add(resultStatus, BorderLayout.NORTH);
        results.add(resultTabs, BorderLayout.CENTER);
        emptyResults();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                selection.tabs(), results);
        split.setResizeWeight(0.38);
        split.setContinuousLayout(true);
        north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        split.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private void wire() {
        fetch.addActionListener(event -> fetchSelected());
        logs.addActionListener(event -> queries.showLogs(this));
        runner.registerRunButton(fetch);
        runner.registerCancelButton(cancel);
        runner.cancelAction(commons::cancelActiveWork);
        runner.onRunningChanged(running -> {
            selection.setRunning(running);
            if (running) {
                selectionStatus.setText(fetchDescription(selection.selected(),
                        (Integer) mediaLimit.getValue()));
            }
            updateActions();
        });
    }

    private void fetchSelected() {
        List<WikidataDynamicObject> subjects = selection.selected();
        if (subjects.isEmpty()) return;
        int limit = (Integer) mediaLimit.getValue();
        selectionStatus.setText(fetchDescription(subjects, limit));
        runner.run(new CommonsDiscoveryQuery(subjects, limit, commons::query,
                        CommonsDiscoveryQuery::loadLabels),
                result -> SwingUtilities.invokeLater(() -> showResult(result)),
                failure -> SwingUtilities.invokeLater(() -> {
                    String message = failure == null || failure.getMessage() == null
                            ? "Commons discovery failed." : failure.getMessage();
                    selectionStatus.setText("Commons discovery failed: " + message);
                    JOptionPane.showMessageDialog(this, message,
                            "Commons discovery failed", JOptionPane.ERROR_MESSAGE);
                }));
    }

    private static String fetchDescription(
            List<WikidataDynamicObject> subjects, int limit) {
        int qids = CommonsDiscoveryQuery.sourceQids(subjects).size();
        int maximum = qids * limit;
        return "Fetching depicts (P180) for " + subjects.size() + " selected instance(s), "
                + "exactly " + qids + " selected QID(s): at most " + limit
                + " Commons files per QID, "
                + maximum + " before duplicate files are merged. No other entity is "
                + "discovered and the project is not changed.";
    }

    private void showResult(CommonsDiscoveryQuery.Result result) {
        resultTabs.removeAll();
        String className = selection.className();
        List<WikidataDynamicObject> subjects = selection.selected();

        MultiView connected = new MultiView();
        connected.context().setFieldSchemaResolver(
                value -> domain.renderedFieldSchema(value,
                        value instanceof WikidataDynamicObject dynamic
                                ? dynamic.typeName() : null));
        connected.context().setTypeSchemaResolver(domain::fieldSchema);
        connected.context().setValueLinker(wikidata.ui.WikidataLinks.valueLinker());
        connected.addSection(className, WikidataDynamicObject.class, subjects,
                domain.configSample(className), domain.structuralFields(className),
                domain.fieldTypes(className));
        connected.addSection("Commons media", MediaRepresentation.class, result.media());
        connected.build(1);
        resultTabs.addTab("Connected objects — " + result.media().size(), connected);

        MediaGroup root = groups(result.media(), subjects);
        GroupMembersView grouped = new GroupMembersView(root, group -> {
            JPanel members = new JPanel(new BorderLayout(4, 4));
            members.setBorder(BorderFactory.createTitledBorder(
                    group.getDisplayName() + " — " + group.getMembers().size()
                            + " Commons file(s)"));
            members.add(SearchableView.builder(group.getMembers())
                    .type(MediaRepresentation.class)
                    .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                    .collapsible(true).build(), BorderLayout.CENTER);
            return members;
        }, JSplitPane.HORIZONTAL_SPLIT, false, 0.28, true);
        grouped.groups().setStatusText(
                "Read-only discovery groups; they are not saved to the project");
        grouped.selectGroup(root, true);
        resultTabs.addTab("Groups", grouped);

        if (!result.withoutMedia().isEmpty()) {
            resultTabs.addTab("No structured depiction — "
                    + result.withoutMedia().size(),
                    selection.instanceView(result.withoutMedia(), className, ignored -> { }));
        }
        long withCreator = result.media().stream()
                .filter(value -> !value.creators().isEmpty()).count();
        long withDate = result.media().stream()
                .filter(value -> !value.creationDates().isEmpty()).count();
        resultStatus.setText("Found " + result.media().size()
                + " distinct Commons file(s) for "
                + (result.selectedSubjects() - result.withoutMedia().size()) + " of "
                + result.selectedSubjects() + " selected entities; " + withCreator
                + " have a QID-valued creator and " + withDate
                + " have a creation date."
                + (result.nonImageFiles() == 0 ? "" : " " + result.nonImageFiles()
                        + " non-image file(s) within the per-QID cap were not shown.")
                + " Results are read-only.");
        selectionStatus.setText("Commons discovery completed. Query logs show every "
                + "request, limit, elapsed time, and failure.");
        updateActions();
    }

    private static MediaGroup groups(
            List<MediaRepresentation> media,
            List<WikidataDynamicObject> subjects) {
        MediaGroup root = new MediaGroup("All Commons media");
        media.forEach(value -> root.addMember(value, false));
        MediaGroup bySubject = root.getOrCreateChild("Depicted entity")
                .role(ViewableGroup.Role.FACET);
        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        subjects.forEach(subject -> labelCounts.merge(depictionGroupLabel(subject), 1,
                Integer::sum));
        for (WikidataDynamicObject subject : subjects) {
            List<MediaRepresentation> matches = media.stream()
                    .filter(value -> value.depictedEntities().contains(subject)).toList();
            String label = depictionGroupLabel(subject);
            if (labelCounts.getOrDefault(label, 0) > 1) {
                label += " [" + subject.identityTypeName() + "]";
            }
            // QID distinguishes same-named source entities; logical type distinguishes
            // two modeled instances that deliberately correspond to the same QID.
            MediaGroup bucket = bySubject.getOrCreateChild(label + " — "
                    + matches.size() + " file(s)")
                    .role(ViewableGroup.Role.BUCKET);
            matches.forEach(value -> bucket.addMember(value, true));
        }
        MediaGroup coverage = root.getOrCreateChild("Field coverage")
                .role(ViewableGroup.Role.FACET);
        coverage(coverage, "Has creator", media,
                value -> !value.creators().isEmpty());
        coverage(coverage, "Missing creator", media,
                value -> value.creators().isEmpty());
        coverage(coverage, "Has creation date", media,
                value -> !value.creationDates().isEmpty());
        coverage(coverage, "Missing creation date", media,
                value -> value.creationDates().isEmpty());
        coverage(coverage, "Has medium", media,
                value -> !value.medium().isEmpty());
        coverage(coverage, "Has collection", media,
                value -> !value.collections().isEmpty());
        coverage(coverage, "Has location/institution", media,
                value -> !value.locations().isEmpty());
        return root;
    }

    private static String depictionGroupLabel(WikidataDynamicObject subject) {
        return subject.getDisplayName() + " (" + subject.qid() + ")";
    }

    private static void coverage(
            MediaGroup parent,
            String name,
            List<MediaRepresentation> values,
            java.util.function.Predicate<MediaRepresentation> predicate) {
        List<MediaRepresentation> matches = values.stream().filter(predicate).toList();
        MediaGroup group = parent.getOrCreateChild(name + " — " + matches.size())
                .role(ViewableGroup.Role.BUCKET);
        matches.forEach(value -> group.addMember(value, true));
    }

    private void emptyResults() {
        resultTabs.removeAll();
        SearchableView empty = SearchableView.builder(List.<MediaRepresentation>of())
                .type(MediaRepresentation.class)
                .emptyMessage("No Commons request has run. Select exact entities, then "
                        + "press Fetch selected entities.")
                .build();
        resultTabs.addTab("Connected objects — 0", empty);
    }

    private void invalidateResults(String message) {
        emptyResults();
        resultStatus.setText(message);
    }

    private void updateActions() {
        boolean running = runner.isRunning();
        int selectedCount = selection == null ? 0 : selection.size();
        mediaLimit.setEnabled(!running);
        fetch.setEnabled(!running && selectedCount > 0
                && selectedCount <= CommonsDiscoveryQuery.MAX_SUBJECTS);
        fetch.setText(running ? "Fetching Commons depictions…"
                : "Fetch " + selectedCount + " selected entit"
                        + (selectedCount == 1 ? "y" : "ies"));
        fetch.setToolTipText(selectedCount == 0
                ? "Select at least one instance with a Wikidata QID."
                : fetchDescription(selection.selected(),
                        (Integer) mediaLimit.getValue()));
    }

    public boolean requestClose(Component owner) {
        return !runner.isRunning()
                || process.swing.SwingCancellationConfirmation.requestForVisibleRun(
                        owner, runner::cancel);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        runner.cancel();
        commons.close();
    }

    public static JFrame openFrame(DomainModel domain, String title) {
        JFrame frame = new JFrame("Commons Discovery — " + title);
        CommonsDiscoveryPanel panel = new CommonsDiscoveryPanel(domain);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(panel);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                if (panel.requestClose(frame)) frame.dispose();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                panel.close();
            }
        });
        frame.setSize(1650, 950);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        return frame;
    }

    private static final class MediaGroup
            extends DefaultViewableGroup<MediaRepresentation, MediaGroup> {
        private MediaGroup(String name) { super(name); }
        @Override protected MediaGroup newChild(String name) {
            return new MediaGroup(name);
        }
        @Override public MediaGroup role(ViewableGroup.Role role) {
            super.role(role);
            return this;
        }
    }
}
