package commons.discovery;

import objectview.demo.MultiView;
import objectview.group.DefaultViewableGroup;
import objectview.group.ViewableGroup;
import objectview.render.GroupMembersView;
import objectview.view.SearchableView;
import objectview.utils.swing.WrapLayout;
import quiz.transform.ui.BoundedInstanceSelection;
import quiz.transform.ui.TransformController;
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
 * The Commons tab of the Discovery workbench: Structured Data on Commons for the
 * workbench's explicit instance selection. Inspecting a class never starts a request, and
 * a completed discovery never changes the loaded model or its saved snapshots.
 */
public final class CommonsDiscoveryPanel extends JPanel implements AutoCloseable {
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

    public CommonsDiscoveryPanel(TransformController domain,
            BoundedInstanceSelection selection) {
        this(domain, selection, new CommonsStructuredDataClient());
    }

    CommonsDiscoveryPanel(TransformController domain, BoundedInstanceSelection selection,
            CommonsStructuredDataClient commons) {
        super(new BorderLayout(6, 6));
        this.domain = java.util.Objects.requireNonNull(domain);
        this.commons = java.util.Objects.requireNonNull(commons);
        this.selection = java.util.Objects.requireNonNull(selection);
        selection.addHost(new BoundedInstanceSelection.Host() {
                    @Override public void selectionChanged(String reason) {
                        invalidateResults(reason);
                    }
                    @Override public void updateActions() {
                        CommonsDiscoveryPanel.this.updateActions();
                    }
                });
        build();
        wire();
        updateActions();
    }

    private void build() {
        JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
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
        runner.cancelAction(commons::cancelActiveWork);
        runner.onRunningChanged(running -> {
            selection.setRunning(this, running);
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
                + maximum + " before duplicate files are merged; then every retained "
                + "image's metadata and depiction scene are loaded in batches of 25. Co-depicted "
                + "entities do not discover more files and the project is not changed.";
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
        long depictionStatements = result.media().stream()
                .mapToLong(value -> value.depictions().size()).sum();
        long qualifierValues = result.media().stream()
                .flatMap(value -> value.depictions().stream())
                .mapToLong(value -> value.qualifiers().size()).sum();
        java.util.Set<String> selectedQids = subjects.stream()
                .map(WikidataDynamicObject::qid).collect(java.util.stream.Collectors.toSet());
        long coDepicted = result.media().stream()
                .flatMap(value -> value.depictions().stream())
                .map(value -> value.depictedEntity().qid())
                .filter(qid -> !selectedQids.contains(qid)).distinct().count();
        resultStatus.setText("Found " + result.media().size()
                + " distinct Commons file(s) for "
                + (result.selectedSubjects() - result.withoutMedia().size()) + " of "
                + result.selectedSubjects() + " selected entities; loaded "
                + depictionStatements + " depiction statement(s), " + coDepicted
                + " distinct co-depicted entities and " + qualifierValues
                + " supported qualifier value(s). " + withCreator
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
        MediaGroup bySubject = root.getOrCreateChild("Selected entity")
                .role(ViewableGroup.Role.FACET);
        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        subjects.forEach(subject -> labelCounts.merge(depictionGroupLabel(subject), 1,
                Integer::sum));
        for (WikidataDynamicObject subject : subjects) {
            List<MediaRepresentation> matches = media.stream()
                    .filter(value -> value.selectedEntities().contains(subject)).toList();
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
        java.util.Set<String> selectedQids = subjects.stream()
                .map(WikidataDynamicObject::qid).collect(java.util.stream.Collectors.toSet());
        MediaGroup coDepicted = root.getOrCreateChild("Co-depicted entity")
                .role(ViewableGroup.Role.FACET);
        Map<String, String> coDepictedLabels = new LinkedHashMap<>();
        Map<String, java.util.Set<MediaRepresentation>> coDepictedMembers =
                new LinkedHashMap<>();
        for (MediaRepresentation value : media) {
            for (DepictionEvidence depiction : value.depictions()) {
                String qid = depiction.depictedEntity().qid();
                if (selectedQids.contains(qid)) continue;
                coDepictedLabels.putIfAbsent(qid, depiction.getDisplayName());
                coDepictedMembers.computeIfAbsent(qid, ignored -> new java.util.LinkedHashSet<>())
                        .add(value);
            }
        }
        coDepictedMembers.forEach((qid, members) -> {
            MediaGroup bucket = coDepicted.getOrCreateChild(coDepictedLabels.get(qid)
                    + " — " + members.size() + " file(s)")
                    .role(ViewableGroup.Role.BUCKET);
            members.forEach(value -> bucket.addMember(value, true));
        });
        MediaGroup qualifierValues = root.getOrCreateChild("Depiction qualifier")
                .role(ViewableGroup.Role.FACET);
        Map<String, String> qualifierLabels = new LinkedHashMap<>();
        Map<String, java.util.Set<MediaRepresentation>> qualifierMembers =
                new LinkedHashMap<>();
        for (MediaRepresentation value : media) {
            for (DepictionEvidence depiction : value.depictions()) {
                for (DepictionQualifier qualifier : depiction.qualifiers()) {
                    qualifierLabels.putIfAbsent(
                            qualifier.facetKey(), qualifier.facetLabel());
                    qualifierMembers.computeIfAbsent(qualifier.facetKey(),
                            ignored -> new java.util.LinkedHashSet<>()).add(value);
                }
            }
        }
        qualifierMembers.forEach((key, members) -> {
            MediaGroup bucket = qualifierValues.getOrCreateChild(qualifierLabels.get(key)
                    + " — " + members.size() + " file(s)")
                    .role(ViewableGroup.Role.BUCKET);
            members.forEach(value -> bucket.addMember(value, true));
        });
        MediaGroup coverage = root.getOrCreateChild("Field coverage")
                .role(ViewableGroup.Role.FACET);
        coverage(coverage, "Has co-depicted entities", media,
                value -> value.depictions().stream().anyMatch(depiction ->
                        !selectedQids.contains(depiction.depictedEntity().qid())));
        coverage(coverage, "Has depiction qualifiers", media,
                value -> value.depictions().stream()
                        .anyMatch(depiction -> !depiction.qualifiers().isEmpty()));
        coverage(coverage, "Has prominent depiction", media,
                value -> value.depictions().stream().anyMatch(DepictionEvidence::prominent));
        coverage(coverage, "Has digital representation", media,
                value -> !value.digitalRepresentations().isEmpty());
        coverage(coverage, "Has main subject", media,
                value -> !value.mainSubjects().isEmpty());
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
        boolean bounded = selectedCount <= CommonsDiscoveryQuery.MAX_SUBJECTS;
        fetch.setEnabled(!running && selectedCount > 0 && bounded);
        fetch.setText(running ? "Fetching Commons depictions…"
                : "Fetch " + selectedCount + " selected entit"
                        + (selectedCount == 1 ? "y" : "ies"));
        fetch.setToolTipText(selectedCount == 0
                ? "Select at least one instance with a Wikidata QID."
                : !bounded ? "One Commons run is bounded to "
                        + CommonsDiscoveryQuery.MAX_SUBJECTS + " entities; remove "
                        + (selectedCount - CommonsDiscoveryQuery.MAX_SUBJECTS)
                        + " from the selection."
                : fetchDescription(selection.selected(),
                        (Integer) mediaLimit.getValue()));
    }

    /** Whether running work may stop: keeping it is the default. */
    public boolean requestStopRun(Component owner) {
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
