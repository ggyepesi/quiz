package domain.datasource.discovery.ui;

import domain.datasource.commons.discovery.ui.CommonsDiscoveryPanel;
import domain.datasource.Datasources;
import domain.datasource.EntityRef;
import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceProvider;
import domain.datasource.api.DatasourceRegistry;
import domain.datasource.api.SourceReferenceSchema;
import domain.datasource.api.discovery.RelatedRecordDiscoveryOperation;
import domain.DomainModel;
import domain.workbench.ui.WorkbenchActionResultNavigator;
import domain.workbench.execution.ui.SwingQuerySession;
import objectview.utils.swing.WrapLayout;
import domain.datasource.pageviews.discovery.ui.PageviewsDiscoveryPanel;
import domain.transform.ui.BoundedInstanceSelection;
import domain.transform.ui.TransformController;
import wikidata.WikidataIds;
import domain.runtime.WikidataDynamicObject;
import domain.datasource.wikipedia.discovery.ui.WikipediaDiscoveryPanel;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One workbench for every discovery source. The instance selection is supplied once —
 * by this standalone workbench or its embedding application — and each source and action
 * reads exactly that selection. The reusable action/result
 * navigator shows source → explicit action → latest result views; navigating never runs
 * an action and moving between sources never asks for the instances again.
 *
 * <p>The selection admits instances with a Wikidata QID, the identity every source
 * starts from. A source that needs more — an English Wikipedia article — counts the
 * selected instances it cannot read and says so; each keeps its own run bound.
 */
public final class DiscoveryWorkbench extends JPanel implements AutoCloseable {
    private static final int DEFAULT_SAMPLE = 12;
    /** The largest per-run bound of any source; a source with a smaller one says so. */
    private static final int MAXIMUM = 50;
    static final String NOT_AVAILABLE = "Configured datasources — not available";

    /** Built-in sources in the order they are shown. */
    public enum Source {
        WIKIPEDIA("wikipedia", "Wikipedia"),
        COMMONS("commons", "Commons"),
        PAGEVIEWS("pageviews", "Wikipedia pageviews"),
        WIKIDATA("wikidata", "Wikidata");

        private final String id;
        private final String title;
        Source(String id, String title) {
            this.id = id;
            this.title = title;
        }
        public String id() { return id; }
        public String title() { return title; }
    }

    private final BoundedInstanceSelection selection;
    private final WikipediaDiscoveryPanel wikipedia;
    private final CommonsDiscoveryPanel commons;
    private final PageviewsDiscoveryPanel pageviews;
    private final WikidataStatementDiscoveryPanel wikidata;
    private final java.util.List<RelatedRecordDiscoveryPanel> relatedRecords =
            new java.util.ArrayList<>();
    private final List<String> catalogProblems;
    private final List<String> unavailableOperations;
    private final WorkbenchActionResultNavigator navigator;
    private final Map<Source, WorkbenchActionResultNavigator.Source> sourceNodes =
            new EnumMap<>(Source.class);
    private final Map<Source, WorkbenchActionResultNavigator.Action> defaultActions =
            new EnumMap<>(Source.class);
    private final Map<String, WorkbenchActionResultNavigator.Action> actionNodes =
            new LinkedHashMap<>();
    private final JLabel selectionStatus = new JLabel(" ");
    private boolean closed;

    public DiscoveryWorkbench(DomainModel model, InitialSelection initialSelection) {
        this(model, initialSelection, Datasources.standard(),
                Datasources.configured().problems());
    }

    /**
     * @param configurationProblems why configured providers are missing from
     *                              {@code datasources}; kept separate from installed
     *                              operations this workbench cannot offer
     */
    DiscoveryWorkbench(DomainModel model, InitialSelection initialSelection,
            DatasourceRegistry datasources,
            List<String> configurationProblems) {
        this(new TransformController(model, new domain.transform.app.DomainSaver()),
                initialSelection, datasources, configurationProblems, null, null);
    }

    /** Mount the same sources and bounded selection in a host's action navigator. */
    public DiscoveryWorkbench(TransformController domain, WorkbenchActionResultNavigator navigator,
            SwingQuerySession queries) {
        this(domain, null, Datasources.standard(), Datasources.configured().problems(), navigator, queries);
    }

    private DiscoveryWorkbench(TransformController domain, InitialSelection initialSelection,
            DatasourceRegistry datasources, List<String> configurationProblems,
            WorkbenchActionResultNavigator hostNavigator, SwingQuerySession queries) {
        super(new BorderLayout(6, 6));
        navigator = hostNavigator == null ? new WorkbenchActionResultNavigator() : hostNavigator;
        this.catalogProblems = List.copyOf(configurationProblems);
        List<String> unavailable = new java.util.ArrayList<>();
        selection = new BoundedInstanceSelection(domain,
                new BoundedInstanceSelection.Host() {
                    @Override public void selectionChanged(String reason) { }
                    @Override public void status(String text) {
                        selectionStatus.setText(text);
                    }
                    @Override public void updateActions() { }
                }, MAXIMUM, DEFAULT_SAMPLE, "entities",
                value -> WikidataIds.isQid(value.qid()), "have Wikidata QIDs");
        wikipedia = new WikipediaDiscoveryPanel(domain, selection);
        commons = new CommonsDiscoveryPanel(domain, selection);
        pageviews = new PageviewsDiscoveryPanel(selection);
        wikidata = new WikidataStatementDiscoveryPanel(domain, selection, queries);
        installRelatedRecordActions(domain, datasources, unavailable);
        this.unavailableOperations = List.copyOf(unavailable);
        build(true, hostNavigator != null);
        if (hostNavigator != null) wikipedia.useHostProjectSave();
        populate(initialSelection);
    }

    /**
     * Embed the source/action/result workbench beside an application's existing instance
     * selection. The supplied selection remains the one input every source reads; this
     * panel contributes no second chooser and navigation is available immediately.
     */
    public DiscoveryWorkbench(TransformController domain,
            BoundedInstanceSelection selection) {
        this(domain, selection, null);
    }

    public DiscoveryWorkbench(TransformController domain,
            BoundedInstanceSelection selection, SwingQuerySession queries) {
        super(new BorderLayout(6, 6));
        navigator = new WorkbenchActionResultNavigator();
        this.catalogProblems = List.copyOf(Datasources.configured().problems());
        this.selection = java.util.Objects.requireNonNull(selection);
        List<String> unavailable = new java.util.ArrayList<>();
        wikipedia = new WikipediaDiscoveryPanel(domain, selection);
        commons = new CommonsDiscoveryPanel(domain, selection);
        pageviews = new PageviewsDiscoveryPanel(selection);
        wikidata = new WikidataStatementDiscoveryPanel(domain, selection, queries);
        installRelatedRecordActions(domain, Datasources.standard(), unavailable);
        this.unavailableOperations = List.copyOf(unavailable);
        build(false, false);
    }

    private void installRelatedRecordActions(TransformController domain,
            DatasourceRegistry datasources, List<String> unavailable) {
        for (DatasourceProvider provider : datasources.providers()) {
            for (DatasourceOperation operation : provider.operations()) {
                if (!(operation instanceof RelatedRecordDiscoveryOperation related)) continue;
                // The selection admits instances by Wikidata QID; an operation that needs
                // another namespace could only fail at Fetch, so it is not offered.
                List<String> namespaces = related.inputReferences().stream()
                        .map(SourceReferenceSchema::namespace).toList();
                if (!namespaces.contains(EntityRef.WIKIDATA)) {
                    unavailable.add(provider.displayName() + " — " + related.displayName()
                            + " is not offered: it needs " + String.join(", ", namespaces)
                            + " entities, and this workbench selects instances by Wikidata "
                            + "QID.");
                    continue;
                }
                relatedRecords.add(new RelatedRecordDiscoveryPanel(
                        domain, selection, provider, related));
            }
        }
    }

    private void build(boolean showSelection, boolean mounted) {

        addBuiltInActions();
        addConfiguredActions();
        if (!catalogProblems.isEmpty() || !unavailableOperations.isEmpty()) {
            List<String> sections = new java.util.ArrayList<>();
            if (!catalogProblems.isEmpty()) {
                sections.add("Not installed:\n\n" + String.join("\n\n", catalogProblems)
                        + "\n\nCorrect the listed installation problem and restart the "
                        + "application to install it.");
            }
            if (!unavailableOperations.isEmpty()) {
                sections.add("Installed, but not offered by this workbench:\n\n"
                        + String.join("\n\n", unavailableOperations)
                        + "\n\nThey are installed, but this workbench cannot supply their "
                        + "input. No file correction or restart is required.");
            }
            JTextArea problems = new JTextArea(String.join("\n\n", sections)
                    + "\n\nThe other sources are unaffected.");
            problems.setEditable(false);
            problems.setLineWrap(true);
            problems.setWrapStyleWord(true);
            navigator.addSource("unavailable", NOT_AVAILABLE,
                    new JScrollPane(problems));
        }
        wireResultNavigation();
        navigator.expandSources();

        if (showSelection) {
            JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
            selection.addControls(controls);
            JPanel north = new JPanel(new BorderLayout(4, 3));
            north.add(controls, BorderLayout.NORTH);
            north.add(selectionStatus, BorderLayout.SOUTH);
            north.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
            if (mounted) {
                JPanel scope = new JPanel(new BorderLayout());
                scope.add(north, BorderLayout.NORTH);
                scope.add(selection.tabs(), BorderLayout.CENTER);
                navigator.addSource("discovery-scope", "Datasource instance selection", scope);
                return;
            }
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                    selection.tabs(), navigator);
            split.setResizeWeight(0.30);
            split.setContinuousLayout(true);
            split.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
            add(north, BorderLayout.NORTH);
            add(split, BorderLayout.CENTER);
        } else {
            add(navigator, BorderLayout.CENTER);
        }
    }

    /** Invalidate the old scope and results after the host replaces the loaded data. */
    public void refreshInstances() {
        wikipedia.refreshProject();
        selection.resetInstances();
        populate(null);
    }

    private void addBuiltInActions() {
        var wikipediaSource = addSource(Source.WIKIPEDIA);
        addAction(Source.WIKIPEDIA, wikipediaSource,
                WikipediaDiscoveryPanel.ANALYZE_RETAINED,
                "Analyze retained articles", wikipedia,
                () -> wikipedia.selectAction(WikipediaDiscoveryPanel.ANALYZE_RETAINED));
        addAction(Source.WIKIPEDIA, wikipediaSource,
                WikipediaDiscoveryPanel.FETCH_ARTICLES,
                "Fetch selected articles", wikipedia,
                () -> wikipedia.selectAction(WikipediaDiscoveryPanel.FETCH_ARTICLES));

        var commonsSource = addSource(Source.COMMONS);
        addAction(Source.COMMONS, commonsSource,
                CommonsDiscoveryPanel.FETCH_DEPICTIONS,
                "Fetch structured depictions", commons, null);

        var pageviewsSource = addSource(Source.PAGEVIEWS);
        addAction(Source.PAGEVIEWS, pageviewsSource,
                PageviewsDiscoveryPanel.READ_VIEWS,
                "Read article views", pageviews, null);

        var wikidataSource = addSource(Source.WIKIDATA);
        addAction(Source.WIKIDATA, wikidataSource,
                WikidataStatementDiscoveryPanel.FETCH_STATEMENTS,
                "Fetch statements and qualifiers", wikidata, null);
    }

    private WorkbenchActionResultNavigator.Source addSource(Source source) {
        WorkbenchActionResultNavigator.Source node = navigator.addSource(
                source.id(), source.title(), null);
        sourceNodes.put(source, node);
        return node;
    }

    private void addAction(Source source,
            WorkbenchActionResultNavigator.Source parent, String id, String label,
            JComponent detail, Runnable reveal) {
        WorkbenchActionResultNavigator.Action action = navigator.addAction(
                parent, id, label, detail, reveal);
        actionNodes.put(id, action);
        defaultActions.putIfAbsent(source, action);
    }

    private void addConfiguredActions() {
        Map<String, WorkbenchActionResultNavigator.Source> configuredSources =
                new LinkedHashMap<>();
        for (RelatedRecordDiscoveryPanel panel : relatedRecords) {
            WorkbenchActionResultNavigator.Source source = configuredSources.computeIfAbsent(
                    panel.sourceId(), id -> navigator.addSource(
                            id, panel.sourceTitle(), null));
            WorkbenchActionResultNavigator.Action action = navigator.addAction(
                    source, panel.actionId(), panel.actionTitle(), panel, null);
            actionNodes.put(panel.actionId(), action);
        }
    }

    private void wireResultNavigation() {
        DiscoveryResultListener listener = new DiscoveryResultListener() {
            @Override public void published(String actionId, JTabbedPane tabs,
                    int primaryTab) {
                WorkbenchActionResultNavigator.Action action = actionNodes.get(actionId);
                if (action == null) {
                    throw new IllegalArgumentException(
                            "No action navigator node for " + actionId);
                }
                navigator.publishResults(action, tabs, primaryTab);
            }

            @Override public void invalidated(String... actionIds) {
                for (String actionId : actionIds) {
                    navigator.clearResults(actionNodes.get(actionId));
                }
            }
        };
        wikipedia.setResultListener(listener);
        commons.setResultListener(listener);
        pageviews.setResultListener(listener);
        wikidata.setResultListener(listener);
        relatedRecords.forEach(panel -> panel.setResultListener(listener));
    }

    private void populate(InitialSelection initial) {
        String requested = initial == null ? null : initial.className();
        boolean populated = initial != null && !initial.availableInstances().isEmpty()
                ? selection.populateScoped(requested, initial.availableInstances())
                : selection.populate(requested);
        if (!populated) {
            selectionStatus.setText("This project has no class instances with Wikidata "
                    + "QIDs. No discovery source can read anything.");
            return;
        }
        if (initial == null || !initial.className().equals(selection.className())) return;
        if (initial.instances().isEmpty()) {
            selectionStatus.setText("Showing exactly " + initial.availableInstances().size()
                    + " " + initial.className() + " instance(s) from " + initial.origin()
                    + ". Select cards and press Add selection, or start a random sample. "
                    + "0 are selected; nothing has been requested.");
            return;
        }
        int omitted = selection.seed(initial.instances(), "The selected instances came "
                + "from " + initial.origin() + ". Run a source to produce results.");
        selectionStatus.setText("Received exactly " + selection.size() + " selected "
                + initial.className() + " instance(s) from " + initial.origin()
                + ". Nothing has been requested."
                + (omitted == 0 ? "" : " " + omitted + " selected instance(s) had no "
                        + "Wikidata QID in this class and were not added.")
                + (selection.size() <= MAXIMUM ? "" : " Every source is disabled until "
                        + "you remove at least " + (selection.size() - MAXIMUM)
                        + " selection(s)."));
    }

    public void showSource(Source source) {
        WorkbenchActionResultNavigator.Action action = defaultActions.get(source);
        if (action != null) navigator.selectAction(action);
        else navigator.selectSource(sourceNodes.get(source));
    }

    /** Add one application-context action beneath an existing datasource source. */
    public WorkbenchActionResultNavigator.Action addContextAction(
            Source source, String id, String label, JComponent detail, Runnable reveal) {
        WorkbenchActionResultNavigator.Source parent = sourceNodes.get(source);
        if (parent == null) throw new IllegalArgumentException("Unknown source " + source);
        WorkbenchActionResultNavigator.Action action = navigator.addAction(
                parent, id, label, detail, reveal);
        actionNodes.put(id, action);
        navigator.expandSources();
        return action;
    }

    public WorkbenchActionResultNavigator.Source contextSource(Source source) {
        return sourceNodes.get(source);
    }

    public BoundedInstanceSelection selection() { return selection; }
    public WikipediaDiscoveryPanel wikipedia() { return wikipedia; }
    public CommonsDiscoveryPanel commons() { return commons; }
    public PageviewsDiscoveryPanel pageviews() { return pageviews; }
    public WikidataStatementDiscoveryPanel wikidata() { return wikidata; }
    public WorkbenchActionResultNavigator navigator() { return navigator; }
    List<RelatedRecordDiscoveryPanel> relatedRecords() {
        return List.copyOf(relatedRecords);
    }

    /** Whether every running source may stop; keeping each is the default. */
    public boolean requestStopRun(Component owner) {
        if (!(wikipedia.requestStopRun(owner) && commons.requestStopRun(owner)
                && pageviews.requestStopRun(owner)
                && wikidata.requestStopRun(owner))) return false;
        for (RelatedRecordDiscoveryPanel panel : relatedRecords) {
            if (!panel.requestStopRun(owner)) return false;
        }
        return true;
    }

    /** Stops running sources, then asks about configuration this window owns. */
    public boolean requestClose(Component owner) {
        if (!(commons.requestStopRun(owner) && pageviews.requestStopRun(owner)
                && wikidata.requestStopRun(owner))) return false;
        for (RelatedRecordDiscoveryPanel panel : relatedRecords) {
            if (!panel.requestStopRun(owner)) return false;
        }
        return wikipedia.requestClose(owner);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        wikipedia.close();
        commons.close();
        pageviews.close();
        wikidata.close();
        relatedRecords.forEach(RelatedRecordDiscoveryPanel::close);
    }

    /** Explicit handoff from another view. The source view supplies the logical class
     *  and instances; discovery never reconstructs that selection from names. */
    public record InitialSelection(
            String className,
            List<WikidataDynamicObject> availableInstances,
            List<WikidataDynamicObject> instances,
            String origin) {
        public InitialSelection(String className,
                List<WikidataDynamicObject> instances, String origin) {
            this(className, List.of(), instances, origin);
        }

        public InitialSelection {
            className = className == null ? "" : className.trim();
            availableInstances = availableInstances == null ? List.of()
                    : availableInstances.stream().filter(java.util.Objects::nonNull)
                    .distinct().toList();
            instances = instances == null ? List.of()
                    : instances.stream().filter(java.util.Objects::nonNull)
                    .distinct().toList();
            origin = origin == null || origin.isBlank() ? "the originating view"
                    : origin.trim();
        }
    }

    public static JFrame openFrame(DomainModel domain, String title) {
        return openWindow(domain, title, null, null, Source.WIKIPEDIA).window();
    }

    /** One window lifecycle for the standalone navigator and contextual callers.
     *  {@code owner} is the workbench that owns the working model, or null when this
     *  window owns it. An owned window's model edits stay pending in its owner, which
     *  guards them, so closing it asks only about running work: offering to "discard"
     *  them here would name a loss that does not happen. */
    public static OpenedWindow openWindow(DomainModel domain, String title,
            InitialSelection initialSelection, Component owner, Source source) {
        JFrame frame = new JFrame("Discovery — " + title);
        DiscoveryWorkbench workbench = forStandaloneWindow(domain, initialSelection);
        workbench.showSource(source);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.add(workbench);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                boolean close = owner == null ? workbench.requestClose(frame)
                        : workbench.requestStopRun(frame);
                if (close) frame.dispose();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                workbench.close();
            }
        });
        frame.setSize(1650, 950);
        frame.setLocationRelativeTo(owner);
        frame.setVisible(true);
        return new OpenedWindow(frame, workbench);
    }

    static DiscoveryWorkbench forStandaloneWindow(
            DomainModel domain, InitialSelection initialSelection) {
        DiscoveryWorkbench workbench = new DiscoveryWorkbench(domain, initialSelection);
        workbench.wikidata.installLabelLookup();
        return workbench;
    }

    public record OpenedWindow(JFrame window, DiscoveryWorkbench workbench) { }
}
