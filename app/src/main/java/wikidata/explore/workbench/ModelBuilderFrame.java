package wikidata.explore.workbench;

import datasource.schema.FieldType;

import wikidata.WikidataIds;

import aux.SplitPaneUtils;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import aux.Constants;
import objectview.Viewable;
import wikidata.explore.codegen.GeneratedViewableRuntime;
import wikidata.explore.codegen.GeneratedViewableSourceGenerator;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;
import wikidata.explore.generation.GenerationPipeline;
import wikidata.explore.generation.GenerationRun;
import wikidata.explore.generation.GenerateDomainProcess;
import process.ProcessStatus;
import process.swing.SwingProcessInputHandler;
import process.swing.SwingProcessRunner;
import wikidata.explore.model.*;
import work.QueryContext;
import wikidata.explore.query.core.QueryFactory;
import wikidata.explore.query.logical.GenerateInstancesQuery;
import wikidata.explore.query.logical.EnrichInstancesQuery;
import wikidata.explore.query.logical.RemapInstancesQuery;
import wikidata.explore.query.swing.QueryObjectResultPanel;
import wikidata.explore.query.swing.SwingQueryRunner;
import wikidata.explore.query.swing.SwingQuerySession;
import wikidata.explore.query.swing.WorkflowLogWindow;
import wikidata.explore.rule.RuleNode;
import wikidata.explore.rule.RuleTreeCompiler;
import wikidata.explore.rule.RuleTreeConfig;
import wikidata.explore.rule.RuleTreeExplainer;
import wikidata.explore.rule.RuleTreeSerializer;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.prefs.Preferences;
import workbench.ExploreByExamplePanel;

public class ModelBuilderFrame extends JFrame {

    private static final Preferences PREFERENCES =
            Preferences.userNodeForPackage(ModelBuilderFrame.class);
    private static final String LAST_DOMAIN = "lastDomain";

    private final WikidataSparqlClient client;

    private final WikidataApiClient apiClient =
            new WikidataApiClient("QuizProject/1.0");

    private final dataset.DomainStorage storage = dataset.DomainStorage.inDefaultLocation();
    /** Survives switching domains: copying here and pasting there is the point. */
    private final ClassClipboard classClipboard = new ClassClipboard();
    private final GeneratedProjectModel projectModel =
            GeneratedProjectModel.constellationDemo();

    private GenerationRun lastRun;
    /** Data-side graph execution history; never folded back into the authored model. */
    private datasource.graph.GraphDiscoveryState graphDiscoveryLedger =
            datasource.graph.GraphDiscoveryState.EMPTY;

    private final SingleRootClassModelPanel classModelPanel =
            new SingleRootClassModelPanel(projectModel);

    private final ModelSourceWorkbenchPanel sourceWorkbench =
            new ModelSourceWorkbenchPanel(projectModel);

    private final QueryObjectResultPanel instancesPanel =
            new QueryObjectResultPanel();

    // Generated instances live in their own window (like the query logs), so
    // the main window is all about configuration.
    private JFrame instancesWindow;

    // Name collisions from the last run (names mapping to >1 entity), shown on
    // demand in their own panel from the instances window.
    private java.util.List<NameCollision> lastCollisions = java.util.List.of();
    // Retained so its window is reused: the view owns the window, so keeping the view
    // is what keeps one window instead of one per press.
    private CardListView nameCollisionsView;

    private final JButton nameCollisionsButton =
            new JButton("Name collisions");

    private final JButton graphFrontierButton =
            new JButton("Graph frontier");

    private final JButton generateButton =
            new JButton("Generate class");

    // Generates EVERY class in the domain into one snapshot (each served as its
    // own type), vs "Generate class" which does the selected class only.
    private final JButton generateDomainButton =
            new JButton("Generate domain");

    // One-shot explanation for a generation launched as the continuation of
    // another workflow (currently graph-frontier expansion).  The next preview
    // consumes it, so an ordinary button press keeps the ordinary caption.
    private String pendingGenerationReason = "";

    // Re-materialize the LAST download with the current model (no re-fetch) — picks
    // up canonicalization / display-name / mapping edits fast.
    private final JButton remapButton =
            new JButton("Remap (no download)");

    // Fetch what a newly DECLARED field needs, over the entities already downloaded —
    // additive, so it costs a property sweep rather than a re-extraction.
    private final JButton enrichButton =
            new JButton("Enrich (declared fields)");

    private final JButton cancelButton =
            new JButton("Cancel");

    private final JButton showInstancesButton =
            new JButton("Show instances");

    private final JButton showStatementsButton =
            new JButton("Statements…");

    // Example-first statement view (#91): sample the selected class + show its
    // instances' statements-with-qualifiers, in its own window.
    private JFrame statementsWindow;
    private StatementSummaryPanel statementsPanel;

    private final JButton showGeneratedSourceButton =
            new JButton("Show generated source");

    private final JButton showRuleTreeButton =
            new JButton("Show rule tree");

    private final JButton showQueryLogsButton =
            new JButton("Show query logs");

    private final JButton openSavedRunButton =
            new JButton("Open saved query log…");

    private final JButton showExplorerButton =
            new JButton("Explorer tools");

    private final JButton showGraphButton =
            new JButton("Model graph");

    private final JButton showEntityKindsButton =
            new JButton("Class admissions");

    private final JButton showGuideButton =
            new JButton("Guide…");
    private final JLabel configurationLockLabel = new JLabel(
            "Configuration locked while a process is running — inspect or cancel to edit.");
    private boolean configurationLocked;

    private JFrame guideWindow;
    private ModelingGuidePanel guidePanel;

    private SelectionViewerPanel selectionsPanel;
    private JFrame entityKindsWindow;

    // Companion window holding the discovery tools (Explore/Sample/Discover/
    // WikiProject/Properties), like the instances + logs windows.
    private JFrame explorerWindow;

    // Graph view of the model (classes = nodes, reference fields = edges).
    private JFrame graphWindow;
    private final ModelGraphPanel graphPanel = new ModelGraphPanel();

    // Domain = the whole project (name + its classes), keyed by name. The combo
    // lists every domain in the dataset registry; New/Rename/Delete manage them.
    private final JComboBox<String> domainBox = new JComboBox<>();
    private final JComboBox<String> modelBox = new JComboBox<>();
    private final JButton newDomainButton = new JButton("New domain");
    private final JButton renameDomainButton = new JButton("Rename domain");
    private final JButton deleteDomainButton = new JButton("Delete domain");
    private JPanel runSection;
    // Guards the combo's listener while we repopulate/select it programmatically.
    private boolean updatingDomainBox = false;

    /**
     * The model's fingerprint when it was last loaded or saved — what "unchanged" means.
     *
     * <p>Not a flag set by every edit path: there are a dozen of those and a new one is
     * added whenever a control is. DomainSave.signature already answers "is this the same
     * model" for the staleness guard, over a COPY, excluding declaration identities and
     * derived vocabulary values — so it says changed when a modeller changed something
     * and not when the model was merely re-read. Reusing it means the two questions
     * cannot drift apart, and a new editor is covered the day it is written.
     */
    private String savedSignature = "";

    /**
     * Where this project lives: the model file it was last loaded from or saved to.
     *
     * <p>Blank for a draft that has never been saved, which is the one state that has no
     * answer. Settled at exactly the moments {@link #savedSignature} is, because they are
     * the same moment — the project became identical to a file on disk.
     */
    private File openModelFile;

    private final JButton loadProjectButton =
            new JButton("Load project");

    // "Load instances", because that is what it loads: the saved objects, into the
    // model that is already open. It was "Load saved", which named neither what is
    // loaded nor what it is loaded into, and asked for a folder the open domain
    // already knows.
    private final JButton loadSavedButton =
            new JButton("Load instances");

    private final JButton saveEverythingButton =
            new JButton("Save domain");

    // Default 1, not 0: depth 0 silently drops every child-object edge (e.g. a
    // constellation's stars), which is a recurring "why are there no stars?"
    // trap. One level of children is the common intent; deeper is opt-in.
    private final JSpinner depthSpinner =
            new JSpinner(new SpinnerNumberModel(1, 0, 5, 1));
    // True while syncing the spinner to the selected class (so the change
    // listener doesn't write the value straight back).
    private boolean syncingDepth = false;

    private final SwingQuerySession querySession;
    private final WorkflowLogWindow logWindow;
    private final SwingProcessRunner processRunner;
    private final QueryFactory queryFactory;

    public ModelBuilderFrame(WikidataSparqlClient client) {
        super("Wikidata Viewable Model Builder");

        this.client = client;
        // The factory owns the datasource↔endpoint bindings; we take a context with every
        // datasource wired (WDQS default + DBpedia). client stays the WDQS primary.
        this.queryFactory = new QueryFactory(
                client, apiClient, "quiz-modelbuilder (ggyepesi@gmail.com)");
        QueryContext queryContext = queryFactory.newContext();
        this.querySession = new SwingQuerySession(queryContext);
        this.logWindow = querySession.logs();
        this.processRunner = new SwingProcessRunner(
                queryContext, logWindow, new SwingProcessInputHandler(this));
        this.selectionsPanel = new SelectionViewerPanel(projectModel, apiClient, client);
        this.selectionsPanel.selections(sourceWorkbench.selections());
        this.selectionsPanel.afterChange(selection -> {
            classModelPanel.refresh();
            if (selection != null) {
                classModelPanel.selectSelection(selection);
            } else {
                sourceWorkbench.showSelections(null);
            }
        });
        this.sourceWorkbench.selectionEditor(selectionsPanel);
        this.sourceWorkbench.domainStatus(() -> new DomainOverviewPanel.Status(
                modelFile().isFile(), snapshotFile().isFile(),
                lastRun == null ? 0 : lastRun.size()));

        // Continue from the saved model (so edits like sharesBorderWith / the
        // Star class persist across restarts) instead of the hard-coded demo.
        loadSavedModelIfPresent();

        buildUi();
        wireActions();
        refreshDomainBox();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                rememberCurrentDomain();
                queryFactory.close();
                sourceWorkbench.close();
            }
        });
        setSize(1750, 950);
        setLocationByPlatform(true);
    }

    /** Release the datasource clients owned by this frame. The primary WDQS client remains
     *  caller-owned, matching QueryFactory's ownership contract. */
    @Override
    public void dispose() {
        rememberCurrentDomain();
        replaceGenerationRun(null);
        queryFactory.close();
        super.dispose();
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));

        // Buttons now live with the panel they act on:
        //  LEFT ("Domain & Classes") — domain CRUD + save/load, the class tree
        //  (with its own class/field actions), and the run controls.
        //  RIGHT ("Configuration") — the artifact viewers + the config editor.
        cancelButton.setEnabled(false);

        JSplitPane main =
                SplitPaneUtils.horizontal(
                        titled("Domain & Classes", buildDomainClassPanel()),
                        buildConfigPanel(),
                        0.34);

        main.setResizeWeight(0.34);

        add(main, BorderLayout.CENTER);

        SwingUtilities.invokeLater(() -> main.setDividerLocation(0.34));
    }

    // LEFT panel: the selected domain, its classes, and the run controls.
    private JPanel buildDomainClassPanel() {
        // Domain section (top): selector + domain-level actions.
        domainBox.setToolTipText("Saved domains. Selecting one opens it.");
        modelBox.setToolTipText("Saved models. Selecting one opens it.");

        JPanel domainPick = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        domainPick.add(new JLabel("Domain:"));
        domainPick.add(domainBox);
        domainPick.add(new JLabel("Model:"));
        domainPick.add(modelBox);
        domainPick.add(newDomainButton);
        domainPick.add(renameDomainButton);
        domainPick.add(deleteDomainButton);

        JPanel domainFiles = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        domainFiles.add(saveEverythingButton);
        domainFiles.add(loadProjectButton);
        loadSavedButton.setToolTipText(
                "Load the instances saved beside this domain's model. "
                        + "Which folder that is follows from the open domain.");
        domainFiles.add(loadSavedButton);

        JPanel domainSection = new JPanel(new GridLayout(0, 1, 0, 0));
        domainSection.add(domainPick);
        domainSection.add(domainFiles);
        // Generation, Explorer and sampling share the process/query runners. Their
        // activity controls stay visible for both project kinds, above the class tree.
        JPanel activity = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        activity.add(new JLabel("Activity:"));
        activity.add(cancelButton);
        activity.add(showQueryLogsButton);
        activity.add(openSavedRunButton);
        domainSection.add(activity);

        // Domain-only run section (bottom): generate, reuse and inspect instances.
        JLabel depthLabel = new JLabel("Depth:");
        String depthTip = "<html>How many levels of <b>child-object</b> reference "
                + "edges to follow when generating.<br>"
                + "0 = just the root class's instances (with scalar and inlined "
                + "list fields).<br>"
                + "1+ = also fetch the referenced objects' own fields, "
                + "recursively, that many levels deep.</html>";
        depthLabel.setToolTipText(depthTip);
        depthSpinner.setToolTipText(depthTip);

        // Two rows so nothing overflows (and "Depth" stays visible) in this
        // narrower panel: generate/depth on top, the result viewers below.
        generateButton.setToolTipText("Generate the SELECTED class only.");
        generateDomainButton.setToolTipText("Generate EVERY class in the domain "
                                                    + "into one snapshot — each served as its own quiz type.");
        JPanel runRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        runRow1.add(generateButton);
        runRow1.add(generateDomainButton);
        runRow1.add(depthLabel);
        runRow1.add(depthSpinner);

        // Reusing what was already downloaded: its own row, and one that stays short.
        // A FlowLayout row inside this fixed-height GridLayout WRAPS out of sight
        // rather than growing, so a row that outgrows a narrow window loses buttons.
        JPanel runRowReuse = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        remapButton.setToolTipText("Re-materialize the last download with the "
                                           + "current model — applies display-name / canonicalization / mapping "
                                           + "edits without re-fetching from Wikidata.");
        runRowReuse.add(remapButton);
        enrichButton.setToolTipText("Load fields declared since the last download — "
                                           + "fetches only those properties, for the entities already in the pool. "
                                           + "Remap cannot show a property nobody fetched.");
        runRowReuse.add(enrichButton);

        JPanel runRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        runRow2.add(showInstancesButton);
        showStatementsButton.setToolTipText("Sample the selected class and show its "
                                                    + "instances' statements — property → values with qualifiers nested, "
                                                    + "plus coverage badges (example-first field discovery, #91).");
        runRow2.add(showStatementsButton);

        runSection = new JPanel(new GridLayout(0, 1, 0, 0));
        runSection.add(runRow1);
        runSection.add(runRowReuse);
        runSection.add(runRow2);

        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(domainSection, BorderLayout.NORTH);
        panel.add(classModelPanel, BorderLayout.CENTER);
        panel.add(runSection, BorderLayout.SOUTH);
        return panel;
    }

    // RIGHT panel: the config editor, with the artifact viewers + Explorer in
    // a header.
    private JPanel buildConfigPanel() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        header.add(showGeneratedSourceButton);
        header.add(showRuleTreeButton);
        header.add(showExplorerButton);
        header.add(showGraphButton);
        showEntityKindsButton.setToolTipText(
                "Map Wikidata evidence such as P31 values to modeled entity kinds");
        header.add(showEntityKindsButton);
        showGuideButton.setToolTipText("Guided build steps for the selected class: "
                                               + "what's done, what's next, the tool for it, and the hint");
        header.add(showGuideButton);

        configurationLockLabel.setForeground(new Color(155, 90, 0));
        configurationLockLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        configurationLockLabel.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(configurationLockLabel, BorderLayout.SOUTH);
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(top, BorderLayout.NORTH);
        panel.add(sourceWorkbench, BorderLayout.CENTER);
        return panel;
    }

    // Lazily-created window hosting the generated-instances result panel.
    private void showInstancesWindow() {
        if (instancesWindow == null) {
            instancesWindow = new JFrame("Generated instances");
            instancesWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            instancesWindow.setLayout(new BorderLayout());

            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            nameCollisionsButton.setToolTipText(
                    "Names shared by several distinct entities (ambiguous quiz "
                            + "answers) — shown as name + List<Source>");
            nameCollisionsButton.addActionListener(e -> showNameCollisions());
            updateNameCollisionsButton();
            toolbar.add(nameCollisionsButton);
            graphFrontierButton.setToolTipText(
                    "Review nodes reached by configured graph discovery but not yet "
                            + "expanded in the reverse direction");
            graphFrontierButton.addActionListener(e -> showGraphFrontier());
            toolbar.add(graphFrontierButton);

            instancesWindow.add(toolbar, BorderLayout.NORTH);
            instancesWindow.add(instancesPanel, BorderLayout.CENTER);
            instancesWindow.setSize(1100, 850);
            instancesWindow.setLocationByPlatform(true);
        }
        // Stamp the generated class (+ count) on the title — a single-class run
        // renders as a plain search view with no type label, so without this
        // there's no on-screen indication of which class you're looking at.
        instancesWindow.setTitle(instancesTitle());
        showAndFocus(instancesWindow);
    }

    /** Presents bounded samples with the same Instances component without replacing
     * the generated domain result that the main Instances window owns. */


    // Lazily-created window hosting the discovery tools moved out of the main
    // frame, so the main window stays focused on domain + configuration.
    // Sample the selected class, then open the statement view on those instances.
    private void showStatementsWindow(wikidata.explore.query.swing.SwingQueryRunner queryRunner) {
        wikidata.explore.rule.RuleNode node = sourceWorkbench.temporaryRuleNodeForSelected();
        if (node == null) {
            logWindow.info("Statements: select a class first.\n");
            return;
        }
        queryRunner.runQuiet(
                new wikidata.explore.query.logical.SampleClassQuery(node, 5),
                result -> {
                    java.util.List<String> qids = new java.util.ArrayList<>();
                    for (java.util.List<Object> row : result.rows()) {
                        if (!row.isEmpty() && row.get(0) != null
                                && WikidataIds.isQid(row.get(0).toString())) {
                            qids.add(row.get(0).toString());
                        }
                    }
                    SwingUtilities.invokeLater(() -> openStatementsWindow(qids));
                },
                ex -> logWindow.info("Statements sample failed: " + ex.getMessage() + "\n"));
    }

    private void openStatementsWindow(java.util.List<String> qids) {
        if (statementsWindow == null) {
            statementsPanel = new StatementSummaryPanel(client);
            // Clicking + on a statement/qualifier flows through the same Add-Field
            // path as Discover: a property → a field, a qualifier → a qualifier
            // field, both on the selected class, opened pre-filled in the editor.
            statementsPanel.onConfigureField((pid, label) ->
                                                     sourceWorkbench.useProperty(pid, label,
                                                                                 wikidata.explore.model.RuleDirection.ROOT_TO_ITEM));
            statementsPanel.onConfigureQualifier((pid, label) ->
                                                         sourceWorkbench.useQualifier(pid, label));
            statementsWindow = new JFrame("Statements — example-first (#91)");
            statementsWindow.add(statementsPanel);
            statementsWindow.setSize(780, 720);
            statementsWindow.setLocationRelativeTo(this);
        }
        showAndFocus(statementsWindow);
        if (!qids.isEmpty()) {
            statementsPanel.showFor(qids);
        }
    }

    private void showExplorerWindow() {
        if (explorerWindow == null) {
            explorerWindow = new JFrame("Explorer tools");
            explorerWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            explorerWindow.setLayout(new BorderLayout());
            explorerWindow.add(sourceWorkbench.helperTools(), BorderLayout.CENTER);
            explorerWindow.setSize(900, 850);
            explorerWindow.setLocationByPlatform(true);
        }
        showAndFocus(explorerWindow);
    }

    // Model-as-graph view: classes are nodes, entity-reference fields are edges.
    // Clicking a node selects that class in the workbench (the graph is a map +
    // selector, not an editor — the class/field panels stay where they are).
    // Browse the domain's Selections — named selections over the entity pool
    // (vocabularies / populations) that are referenced but never served. A
    // Selection's content is inspectable even though it isn't a product; declare a
    // vocabulary and see its members.
    private void showEntityKindsWindow() {
        if (entityKindsWindow == null) {
            entityKindsWindow = new JFrame("Class admission evidence");
            entityKindsWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            entityKindsWindow.setSize(700, 420);
            entityKindsWindow.setLocationByPlatform(true);
        }
        // Labels for the vocabulary picker come from the loaded pool — the same objects
        // the vocabulary was derived from, so a value always reads as what it is.
        // REACHABLE, not the roots: a vocabulary value (Q5 "human") is a nested field
        // value and never a top-level object, so a roots-only scan finds no labels at all.
        java.util.Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();
        // The reachable graph can be large. Warm the optional label cache away from
        // the EDT; until it is ready the picker safely displays the QID fallback.
        GenerationRun labelRun = lastRun;
        if (labelRun != null && labelRun.dynamicObjects() != null) {
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() {
                    for (WikidataDynamicObject o :
                            wikidata.explore.extract.WikidataObjectGraph.reachable(
                                    labelRun.dynamicObjects())) {
                        if (o != null && wikidata.WikidataIds.isQid(o.qid())
                                && o.getDisplayName() != null
                                && !o.getDisplayName().isBlank()) {
                            labels.putIfAbsent(o.qid(), o.getDisplayName());
                        }
                    }
                    return null;
                }
            }.execute();
        }
        entityKindsWindow.getContentPane().removeAll();
        entityKindsWindow.add(new EntityKindRulesPanel(projectModel, this::modelChanged,
                labels::get,
                (seed, onPicked) -> ExploreByExamplePanel.showPicker(
                        entityKindsWindow, querySession.runner(), seed, false,
                        (qid, label) -> onPicked.accept(qid))));
        entityKindsWindow.revalidate();
        entityKindsWindow.repaint();
        showAndFocus(entityKindsWindow);
        // Content built fresh is built enabled, and the lock was applied to the content
        // this just replaced. Re-applying it here is what keeps the window from being
        // the one editable surface during a run.
        if (configurationLocked) {
            EditableComponents.setEditable(entityKindsWindow.getContentPane(), false);
        }
    }

    private void showGraphWindow() {
        if (graphWindow == null) {
            graphWindow = new JFrame("Model graph");
            graphWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            graphWindow.setLayout(new BorderLayout());
            graphWindow.add(new JScrollPane(graphPanel), BorderLayout.CENTER);
            graphWindow.setSize(720, 600);
            graphWindow.setLocationByPlatform(true);
            graphPanel.onClassSelected(name -> {
                GeneratedClassModel c = classByName(name);
                if (c != null) {
                    classModelPanel.selectClass(c); // fires the tree listener
                }
            });
            graphPanel.onFieldSelected((className, fieldName) -> {
                GeneratedClassModel c = classByName(className);
                if (c == null) {
                    return;
                }
                c.fields().stream()
                 .filter(f -> f != null && fieldName.equals(f.name()))
                 .findFirst()
                 .ifPresent(classModelPanel::selectField);
            });
            // Double-click a node → jump into live Explore of that class's
            // membership target (its Wikidata type/award), unifying the model
            // graph with the navigation canvas.
            graphPanel.onClassExplore(name -> {
                GeneratedClassModel c = classByName(name);
                if (c == null) {
                    return;
                }
                String qid = c.effectiveInstanceMapping(projectModel).sourceQid();
                if (qid == null || !WikidataIds.isQid(qid)) {
                    logWindow.info("Class \"" + name + "\" has no membership target "
                                           + "QID to explore — set a Relation target first.");
                    return;
                }
                showExplorerWindow();
                sourceWorkbench.explorePanel().exploreQid(qid, name);
            });
        }
        graphPanel.setModel(projectModel);
        showAndFocus(graphWindow);
    }

    private String instancesTitle() {
        if (lastRun == null) {
            return "Generated instances";
        }
        // Per-class counts, biggest first — the total alone was misleading (it
        // reads as if every instance is the root class, when it's the whole pool
        // across classes, e.g. films + nominations + categories).
        // Count DISTINCT entities per class, by QID — the served/saved artifact is
        // keyed by QID (one instance per QID), so counting raw pool objects
        // over-reports: a QID that is both a class root and a bare reference child, or
        // a blank-QID ref, is one saved entity but several pool objects. Skipping
        // non-QID objects + deduping by QID makes this preview match the reload.
        java.util.Map<String, java.util.Set<String>> qidsByType =
                new java.util.LinkedHashMap<>();
        if (lastRun.dynamicObjects() != null) {
            for (WikidataDynamicObject o : lastRun.dynamicObjects()) {
                if (o == null || o.typeName() == null || o.typeName().isBlank()
                        // untyped sentinel: demoted duplicate statements / bare
                        // reference children, not first-class class instances.
                        || "WikidataDynamicObject".equals(o.typeName())) {
                    continue;
                }
                String qid = o.qid();
                if (qid == null || !WikidataIds.isQid(qid)) {
                    continue;   // not a saved entity — don't inflate the count
                }
                qidsByType.computeIfAbsent(
                        o.typeName(), k -> new java.util.HashSet<>()).add(qid);
            }
        }
        if (qidsByType.isEmpty()) {
            return "Generated instances  (" + lastRun.size() + ")";
        }
        int total = qidsByType.values().stream().mapToInt(java.util.Set::size).sum();
        String breakdown = qidsByType.entrySet().stream()
                                     .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                                     .map(e -> e.getKey() + " " + e.getValue().size())
                                     .collect(java.util.stream.Collectors.joining(", "));
        return "Generated instances — " + breakdown
                + "  (" + total + " distinct)" + partsNote();
    }

    /** A single-class preview deliberately does NOT materialize owned components: one
     *  empty part per instance answers neither question a preview asks. Say so on the
     *  panel, or their absence reads as a fault in the model. */
    private String partsNote() {
        boolean modelHasParts = projectModel.classes().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(clazz -> clazz.fields().stream().anyMatch(field ->
                        field != null && field.type()
                                == datasource.schema.FieldType.ENTITY
                                && field.mapping().productionKind()
                                == wikidata.explore.model.FieldProductionKind.OWNED_COMPONENT));
        if (!modelHasParts || lastRun == null || lastRun.dynamicObjects() == null) {
            return "";
        }
        boolean anyPart = lastRun.dynamicObjects().stream()
                .anyMatch(o -> o != null && o.isPart());
        return anyPart ? "" : "   ⚠ parts not materialized (preview — "
                + "Generate domain or Enrich produces them with their values)";
    }

    private void wireActions() {
        SwingQueryRunner queryRunner = querySession.runner();

        queryRunner.onRunningChanged(ignored -> updateConfigurationLock());
        processRunner.onRunningChanged(ignored -> updateConfigurationLock());

        queryRunner.registerCancelButton(cancelButton);
        processRunner.registerCancelButton(cancelButton);
        // The lock owns these, not registerRunButton: Remap and Enrich also require a
        // previous run, and two owners for one button's enabled state means whichever
        // runs last wins — which was only correct while the listeners happened to fire
        // in the right order.
        queryRunner.registerRunButton(showGeneratedSourceButton);
        queryRunner.cancelAction(client::cancelCurrentQuery);

        sourceWorkbench.setQueryRunner(queryRunner);
        sourceWorkbench.log(logWindow::info);

        sourceWorkbench.afterChange(v -> modelChanged());
        classModelPanel.onCopyClass(this::copyClassConfiguration);
        classModelPanel.onPasteClass(this::pasteClassConfiguration);
        classModelPanel.onImportClass(this::importClassConfiguration);
        classModelPanel.pasteAvailable(() -> classClipboard.canPasteInto(projectModel));

        sourceWorkbench.afterApplyField(f -> {
            modelChanged();
            classModelPanel.selectField(f);
            sourceWorkbench.edit(f);
        });

        sourceWorkbench.onGraphSelection(target -> {
            if (target instanceof GeneratedClassModel clazz) {
                classModelPanel.selectClass(clazz);
            } else if (target instanceof GeneratedFieldModel field) {
                classModelPanel.selectField(field);
            }
        });

        // A field added from a tool in the Explorer window is otherwise
        // invisible — bring the main config window forward and say where it went
        // (only on an explicit add, NOT on the applyEdits a Discover run does,
        // which used to steal focus from the Explorer window).
        sourceWorkbench.onFieldAddedFromTool(f -> {
            GeneratedClassModel owner = activeClass();
            logWindow.info("Added field \"" + f.name() + "\""
                                   + (owner == null ? "" : " on class \"" + owner.className() + "\"")
                                   + " — shown in the main window.");
            toFront();
            requestFocus();
        });

        classModelPanel.addTreeSelectionListener(e -> {
            Object selected = classModelPanel.selectedUserObject();
            // The domain node is navigation context, not a configurable object. Keep
            // the current editor visible and let the domain row remain highlighted.
            if (SingleRootClassModelPanel.isConfigurable(selected)) {
                sourceWorkbench.changeSelection(selected);
            }
            // Per-class depth: show the newly-selected class's saved depth.
            syncDepthSpinnerToActiveClass();
            // Mirror the selection onto the graph (the two-way "connection").
            GeneratedClassModel sel = activeClass();
            graphPanel.setSelectedClass(sel == null ? "" : sel.className());
            if (guidePanel != null && guideWindow != null && guideWindow.isVisible()) {
                guidePanel.refresh();
            }
        });

        // Store depth edits onto the currently-selected class.
        depthSpinner.addChangeListener(e -> {
            if (syncingDepth) return;
            GeneratedClassModel c = activeClass();
            if (c != null) {
                c.generationDepth(((Number) depthSpinner.getValue()).intValue());
            }
        });

        queryRunner.wireButton(
                generateButton,
                this::acceptGenerationRun,
                () -> {
                    sourceWorkbench.applyEdits();
                    GeneratedProjectModel rooted = modelRootedAtSelected();
                    String problem = membershipProblem(rooted.rootClass());
                    if (problem != null) {
                        warnNothingToGenerate(problem);
                        return null; // don't run — avoids a silent empty panel
                    }
                    return new GenerateInstancesQuery(
                            rooted,
                            ((Number) depthSpinner.getValue()).intValue());
                },
                this::reportGenerationError);

        generateDomainButton.addActionListener(e -> {
            if (processRunner.isRunning() || queryRunner.isRunning()) {
                return;
            }
            String generationReason = pendingGenerationReason;
            pendingGenerationReason = "";
            try {
                sourceWorkbench.applyEdits();
                // Per-class depth (saved on each class) is used; sync the
                // active class's spinner value first.
                GeneratedClassModel active = activeClass();
                if (active != null) {
                    active.generationDepth(
                            ((Number) depthSpinner.getValue()).intValue());
                }
                // If NO class can be populated, say so instead of opening an
                // empty instances panel.
                boolean anyGeneratable = projectModel.classes().stream()
                                                     .anyMatch(c -> membershipProblem(c) == null);
                if (!anyGeneratable) {
                    warnNothingToGenerate(membershipProblem(
                            projectModel.rootClass()));
                    return;
                }
                GeneratedProjectModel snapshot = projectModel.copy();
                wikidata.explore.generation.WikidataGraphDiscoveryState
                        .applyExpansionLedger(snapshot, graphDiscoveryLedger);
                process.ProcessWorkflowPipeline generationPipeline =
                        wikidata.explore.generation.GenerateDomainPipeline.configured(snapshot);
                var executionSettings =
                        new wikidata.explore.generation.GenerationExecutionSettings();
                GenerateDomainProcess generation =
                        new GenerateDomainProcess(snapshot, generationPipeline, executionSettings);
                java.util.List<objectview.Viewable> classCards = snapshot.classes().stream()
                        .map(model -> {
                            quiz.transform.DynamicViewable card =
                                    new quiz.transform.DynamicViewable(
                                            model.className(), model.className());
                            card.type("Generated class");
                            card.put("Depth", model.generationDepth());
                            card.put("Fields", model.fields().size());
                            return (objectview.Viewable) card;
                        }).toList();
                process.swing.workflow.ProcessWorkflowAction<
                        GenerationRun, GenerationRun> action =
                        new process.swing.workflow.ProcessWorkflowAction<>() {
                            @Override public String id() { return "generate-domain"; }
                            @Override public process.ProcessWorkflowPipeline
                                    pipeline() { return generationPipeline; }
                            @Override public javax.swing.JComponent executionSettings() {
                                return new wikidata.explore.generation.GenerationExecutionSettingsPanel(
                                        executionSettings);
                            }
                            @Override public boolean applyAllowed(
                                    process.ProcessStatus status) {
                                return status != process.ProcessStatus.PARTIAL
                                        || !executionSettings.requireComplete();
                            }
                            @Override public process.swing.workflow.ProcessWorkflowPlan plan() {
                                String title = generationReason.isBlank()
                                        ? "Generate domain"
                                        : "Generate domain — " + generationReason;
                                String description = generationReason.isBlank()
                                        ? "Generate every configured class into one shared domain snapshot."
                                        : "Regenerate every configured class after "
                                        + generationReason.toLowerCase(java.util.Locale.ROOT)
                                        + ". The selected frontier nodes are now population seeds.";
                                return new process.swing.workflow.ProcessWorkflowPlan(
                                        title, description,
                                        java.util.List.of(
                                                new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                                        "Classes", classCards)));
                            }
                            @Override public process.Process<GenerationRun> process() {
                                return generation;
                            }
                            @Override public process.swing.workflow.ProcessWorkflowResults<
                                    GenerationRun> results(
                                            process.ProcessOutcome<GenerationRun> outcome) {
                                return runResults("Generate domain", "generation",
                                        snapshot.name(), outcome.result(), outcome,
                                        generationPipeline);
                            }
                            @Override public void apply(java.util.List<GenerationRun> decisions) {
                                if (!decisions.isEmpty()) acceptGenerationRun(decisions.get(0));
                            }
                            @Override public void afterApply() {
                                // Applying installs the data while the workflow dialog still
                                // exists. Reveal the destination only after that dialog closes,
                                // otherwise its close can steal activation back on macOS.
                                showInstancesWindow();
                            }
                        };
                logWindow.registerPipeline(
                        "Generate domain — " + snapshot.name(), generationPipeline,
                        snapshot.name(),
                        storage.snapshotFile(snapshot.name()).getParentFile().toPath());
                process.swing.workflow.SwingProcessWorkflow.start(
                        this, processRunner, action, this::openPipelineReference);
            } catch (Exception ex) {
                reportGenerationError(ex);
            }
        });

        remapButton.addActionListener(e -> {
            if (processRunner.isRunning() || queryRunner.isRunning()) return;
            try {
                    sourceWorkbench.applyEdits();
                    if (lastRun == null) {
                        warnNothingToGenerate(
                                "Nothing to remap yet — run Generate domain (or "
                                        + "instances) first.");
                        return;
                    }
                    // Reuse the last download; re-materialize with the current model
                    // so canonicalization / display-name edits apply without a
                    // re-fetch. Valid for local edits (same extraction).
                    GeneratedProjectModel snapshot = projectModel.copy();
                    var operationSettings =
                            new wikidata.explore.generation.GenerationExecutionSettings();
                    wikidata.explore.generation.RemapScope replayableScope =
                            wikidata.explore.generation.RemapScope.of(lastRun);
                    var pipeline = wikidata.explore.generation.GenerateDomainPipeline
                            .configuredRemap(
                            java.util.List.of(lastRun.dynamicObjects().size()
                                    + " existing objects", snapshot.classes().size()
                                    + " configured classes"),
                            replayableScope.retransform());
                    // The remap knows what it can replay; it says so rather than
                    // leaving the host to guess from the operation's name.
                    wikidata.explore.generation.RemapScope replayable =
                            wikidata.explore.generation.RemapScope.of(lastRun);
                    startGenerationOperation(
                            "Remap domain", "Preview local model changes without downloading data.",
                            "remap", new RemapInstancesQuery(lastRun, snapshot,
                                    wikidata.explore.generation.RunSteps.of(pipeline)),
                            pipeline,
                            snapshot, operationSettings, false,
                            scope -> scope.put("Self-reference rule", replayable.retransform()
                                    ? "Will run during reification; its keep/drop "
                                            + "decisions are shown in the results"
                                    : "Will not run — this run has no pre-reification "
                                            + "pool to replay from"));
            } catch (Exception ex) {
                reportGenerationError(ex);
            }
        });

        enrichButton.addActionListener(e -> {
            if (processRunner.isRunning() || queryRunner.isRunning()) return;
            try {
                    sourceWorkbench.applyEdits();
                    if (lastRun == null) {
                        warnNothingToGenerate(
                                "Nothing to enrich yet — run Generate domain (or "
                                        + "instances) first.");
                        return;
                    }
                    // Additive: a field declared since the download needs its property
                    // fetched for the entities already here, not a new extraction.
                    GeneratedProjectModel snapshot = projectModel.copy();
                    var operationSettings =
                            new wikidata.explore.generation.GenerationExecutionSettings();
                    var pipeline = wikidata.explore.generation.GenerateDomainPipeline
                            .configuredEnrich(enrichmentDetails(snapshot, lastRun));
                    startGenerationOperation(
                            "Enrich domain",
                            "Add newly declared values to the existing population without rediscovery.",
                            "enrich", new EnrichInstancesQuery(
                                    lastRun, snapshot, operationSettings,
                                    wikidata.explore.generation.RunSteps.of(pipeline)),
                            pipeline,
                            snapshot, operationSettings, true,
                            scope -> scope.put("Self-reference rule",
                                    "Will not run — Enrich adds values without "
                                            + "reifying statements"));
            } catch (Exception ex) {
                reportGenerationError(ex);
            }
        });

        sourceWorkbench.onReloadField(this::forgetFetchedDeclaration);

        showInstancesButton.addActionListener(e -> showInstancesWindow());
        showStatementsButton.addActionListener(e -> showStatementsWindow(queryRunner));

        showExplorerButton.addActionListener(e -> showExplorerWindow());
        showGraphButton.addActionListener(e -> showGraphWindow());
        showEntityKindsButton.addActionListener(e -> showEntityKindsWindow());

        // Wire the WikiProject seed panel to the selected class: pick one entity
        // as the membership type, or add/replace the class's seed-QID set.
        // WikiProject + Explore share the same targets: the active class's
        // seed-QID set (instances) or its membership type.
        WikiProjectSeedPanel wp = sourceWorkbench.wikiProjectPanel();
        wp.onUseAsSourceQid(this::useSourceQid);
        wp.onAddSeedQids(qids -> addSeedQids(qids, false));
        wp.onReplaceSeedQids(qids -> addSeedQids(qids, true));

        ExploreByExamplePanel ex = sourceWorkbench.explorePanel();
        ex.onAddSeedQids(qids -> addSeedQids(qids, false));
        ex.onAddRelationTargets(this::addRelationTargets);
        ex.onUseAsSourceQid(this::useSourceQid);

        CategorySeedPanel cat = sourceWorkbench.categoryPanel();
        cat.onUseAsSourceQid(this::useSourceQid);
        cat.onAddSeedQids(qids -> addSeedQids(qids, false));
        cat.onReplaceSeedQids(qids -> addSeedQids(qids, true));


        domainBox.addActionListener(e -> {
            if (updatingDomainBox) return;
            Object sel = domainBox.getSelectedItem();
            if (sel != null && !sel.toString().equals(projectModel.name())) {
                switchToDomain(sel.toString());
            }
        });
        modelBox.addActionListener(e -> {
            if (updatingDomainBox) return;
            Object sel = modelBox.getSelectedItem();
            if (sel != null && !sel.toString().equals(projectModel.name())) {
                switchToDomain(sel.toString());
            }
        });
        newDomainButton.addActionListener(e -> newProject());
        renameDomainButton.addActionListener(e -> renameDomain());
        deleteDomainButton.addActionListener(e -> deleteDomain());

        loadProjectButton.addActionListener(e -> loadProject());

        loadSavedButton.addActionListener(e -> loadSavedInstances());

        saveEverythingButton.addActionListener(e -> saveEverything());

        showGeneratedSourceButton.addActionListener(e ->
                                                            showGeneratedSource());

        showRuleTreeButton.addActionListener(e -> showRuleTree());
        showGuideButton.addActionListener(e -> showGuide());

        showQueryLogsButton.addActionListener(e -> querySession.showLogs(this));
        openSavedRunButton.addActionListener(e -> {
            wikidata.explore.query.swing.RunInspectorFrame inspector =
                    new wikidata.explore.query.swing.RunInspectorFrame(
                            projectDataDir().toPath());
            inspector.setLocationRelativeTo(this);
            inspector.setVisible(true);
            inspector.chooseAndOpen(this);
        });

        sourceWorkbench.edit(projectModel.rootClass());
        refreshProjectKindUi();
    }

    private static java.util.List<String> enrichmentDetails(
            GeneratedProjectModel snapshot, GenerationRun previous) {
        java.util.List<String> details = new java.util.ArrayList<>();
        details.add(previous.dynamicObjects().size() + " existing objects");
        for (GeneratedClassModel clazz : snapshot.classes()) {
            java.util.List<String> properties = clazz.fields().stream()
                    .map(field -> field.mapping() == null
                            ? "" : field.mapping().propertyPid())
                    .filter(pid -> pid != null && !pid.isBlank())
                    .distinct().toList();
            if (!properties.isEmpty()) {
                details.add(clazz.className() + " — " + String.join(", ", properties));
            }
        }
        if (details.size() == 1) details.add("No property-backed fields configured");
        return details;
    }

    /** What the configured rules account for in the run this operation would start from
     *  — asked of the pool as it stands, so the plan can state it without running. */
    private java.util.List<wikidata.explore.generation.RuleEffects.Effect> ruleEffects(
            GeneratedProjectModel snapshot) {
        if (lastRun == null || lastRun.dynamicObjects() == null) {
            return java.util.List.of();
        }
        return wikidata.explore.generation.RuleEffects.of(
                snapshot, lastRun.dynamicObjects());
    }

    /** A rendered explanation of one rule bucket, before its instance cards. */
    private static quiz.transform.DynamicViewable ruleEffectSummary(
            wikidata.explore.generation.RuleEffects.Effect effect,
            String phase) {
        quiz.transform.DynamicViewable summary = new quiz.transform.DynamicViewable(
                phase + "-rule-" + Integer.toHexString(effect.rule().hashCode()),
                effect.rule());
        summary.type("Rule effect");
        summary.put("Action", effect.detail());
        summary.put("Instances", effect.size());
        return summary;
    }

    /**
     * The results of any run that changes the pool, read the same way: a summary, one
     * tab per rule that can name what it accounts for, and the whole pool last, labelled
     * as the context it is rather than as the answer.
     *
     * <p>Shared because Generate had its own copy. Two operations presenting their
     * outcome differently is how a reader learns to distrust both, and it is how the
     * rule tabs would have reached Remap while the run that actually drops records —
     * a generation — kept showing 47036 cards and a number.
     */
    private process.swing.workflow.ProcessWorkflowResults<GenerationRun> runResults(
            String title, String phaseId, String domainName,
            GenerationRun run, process.ProcessOutcome<GenerationRun> outcome,
            process.ProcessWorkflowPipeline pipeline) {

        java.util.List<wikidata.explore.generation.RuleEffects.Effect> effects =
                wikidata.explore.generation.RuleEffects.inPipelineOrder(
                wikidata.explore.generation.RuleEffects.fromRun(
                        run.fieldCoverage(), run.selfReferenceAudit(),
                        run.ownedCompositionAudit(), run.kindClassificationAudit(),
                        run.projectionAudit()), pipeline);

        quiz.transform.DynamicViewable summary = new quiz.transform.DynamicViewable(
                phaseId + "-summary", domainName);
        summary.type("Generation result");
        // The pool size is context, not a result: for a Remap it barely moves, so
        // leading with it says nothing about what the run did.
        summary.put("What the rules account for",
                wikidata.explore.generation.RuleEffects.describe(effects));
        summary.put("Self-reference audit", run.selfReferenceAudit().description());
        summary.put("Owned composition", run.ownedCompositionAudit().description());
        summary.put("Kind classification", run.kindClassificationAudit().description());
        summary.put("Projections", run.projectionAudit().description());
        summary.put("Objects in the pool", run.size());

        java.util.List<process.swing.workflow.ProcessWorkflowResults.Tab<GenerationRun>>
                tabs = new java.util.ArrayList<>();
        tabs.add(new process.swing.workflow.ProcessWorkflowResults.Tab<>(
                "Summary", java.util.List.of(
                        new process.swing.workflow.ProcessWorkflowResults.Card<>(
                                summary, () -> run, true))));
        for (wikidata.explore.generation.RuleEffects.Effect effect : effects) {
            java.util.List<process.swing.workflow.ProcessWorkflowResults.Card<GenerationRun>>
                    cards = new java.util.ArrayList<>();
            cards.add(new process.swing.workflow.ProcessWorkflowResults.Card<>(
                    ruleEffectSummary(effect, phaseId + "-result"), () -> null, false));
            cards.addAll(instanceCards(effect.instances()));
            tabs.add(new process.swing.workflow.ProcessWorkflowResults.Tab<>(
                    effect.phase().label() + " · " + effect.title(), cards));
        }
        tabs.add(new process.swing.workflow.ProcessWorkflowResults.Tab<>(
                "All objects (" + run.size() + ")", instanceCards(run.instances())));
        return new process.swing.workflow.ProcessWorkflowResults<>(
                title + " — results", outcome.summary(), "Accept", tabs);
    }

    private static java.util.List<process.swing.workflow.ProcessWorkflowResults.Card<
            GenerationRun>> instanceCards(java.util.List<? extends objectview.Viewable> of) {
        return of.stream()
                .map(instance -> new process.swing.workflow.ProcessWorkflowResults.Card<
                        GenerationRun>(instance, () -> null, false))
                .toList();
    }

    private void startGenerationOperation(
            String title, String description, String phaseId,
            work.Query<GenerationRun> query,
            process.ProcessWorkflowPipeline pipeline,
            GeneratedProjectModel snapshot,
            wikidata.explore.generation.GenerationExecutionSettings executionSettings,
            boolean networked,
            java.util.function.Consumer<quiz.transform.DynamicViewable> describeScope) {
        var operation = new wikidata.explore.generation.GenerationOperationProcess(
                title, description, phaseId, query, pipeline, executionSettings, networked);
        quiz.transform.DynamicViewable summary = new quiz.transform.DynamicViewable(
                title.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'), snapshot.name());
        summary.type("Domain operation");
        summary.put("Existing objects", lastRun == null ? 0 : lastRun.size());
        summary.put("Classes", snapshot.classes().size());
        // Whether reification runs is a property of the operation, and the caller that
        // builds the operation knows it. Deciding it here by matching the phase id
        // against "remap" would infer behaviour from a name — the mistake the model
        // itself is guarded against — and a rename would silently drop the warning.
        if (describeScope != null) {
            describeScope.accept(summary);
        }
        process.swing.workflow.ProcessWorkflowAction<GenerationRun, GenerationRun> action =
                new process.swing.workflow.ProcessWorkflowAction<>() {
                    @Override public String id() { return phaseId; }
                    @Override public process.ProcessWorkflowPipeline pipeline() {
                        return pipeline;
                    }
                    @Override public javax.swing.JComponent executionSettings() {
                        return new wikidata.explore.generation.GenerationExecutionSettingsPanel(
                                executionSettings, networked);
                    }
                    @Override public boolean applyAllowed(process.ProcessStatus status) {
                        return status != process.ProcessStatus.PARTIAL
                                || !executionSettings.requireComplete();
                    }
                    @Override public process.swing.workflow.ProcessWorkflowPlan plan() {
                        // A rule that can say WHICH instances it accounts for gets a tab
                        // of its own, so the plan states what this run is about to be
                        // about — the same reading a generation's result already gives,
                        // where the class is the configuration and the instances are it
                        // filled in. Evaluated, not applied: nothing here changes a thing.
                        java.util.List<process.swing.workflow.ProcessWorkflowPlan.Tab> tabs =
                                new java.util.ArrayList<>();
                        tabs.add(new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                "Scope", java.util.List.of(summary)));
                        for (wikidata.explore.generation.RuleEffects.Effect effect
                                : ruleEffects(snapshot)) {
                            java.util.List<objectview.Viewable> contents =
                                    new java.util.ArrayList<>();
                            contents.add(ruleEffectSummary(effect, phaseId + "-plan"));
                            contents.addAll(effect.instances());
                            tabs.add(new process.swing.workflow.ProcessWorkflowPlan.Tab(
                                    effect.title(), contents));
                        }
                        return new process.swing.workflow.ProcessWorkflowPlan(
                                title, description, tabs);
                    }
                    @Override public process.Process<GenerationRun> process() {
                        return operation;
                    }
                    @Override public process.swing.workflow.ProcessWorkflowResults<GenerationRun>
                            results(process.ProcessOutcome<GenerationRun> outcome) {
                        return runResults(title, phaseId, snapshot.name(),
                                outcome.result(), outcome, pipeline);
                    }
                    @Override public void apply(java.util.List<GenerationRun> decisions) {
                        if (!decisions.isEmpty()) acceptGenerationRun(decisions.get(0));
                    }
                    @Override public void afterApply() {
                        showInstancesWindow();
                    }
                };
        logWindow.registerPipeline(title + " — " + snapshot.name(), pipeline,
                snapshot.name(),
                storage.snapshotFile(snapshot.name()).getParentFile().toPath());
        process.swing.workflow.SwingProcessWorkflow.start(
                this, processRunner, action, this::openPipelineReference);
    }

    /** Turns the pipeline explanation into a map back to the configuration that
     * produced it. Property references live in Explore; classes and fields live in
     * the model tree. Unknown/stale references remain harmless in saved runs. */
    private void openPipelineReference(process.PhaseExplanation.ModelReference reference) {
        if (reference == null) return;
        switch (reference.kind()) {
            case CLASS -> {
                GeneratedClassModel clazz = classByName(reference.name());
                if (clazz != null) classModelPanel.selectClass(clazz);
            }
            case FIELD -> {
                GeneratedClassModel owner = classByName(reference.owner());
                if (owner == null) return;
                owner.fields().stream()
                        .filter(field -> reference.name().equals(field.name()))
                        .findFirst()
                        .ifPresent(classModelPanel::selectField);
            }
            case PROPERTY -> {
                showExplorerWindow();
                sourceWorkbench.showProperties();
            }
            case KIND_RULE -> showEntityKindsWindow();
            case ROLE, PHASE -> { /* Informational until those editors expose navigation. */ }
        }
    }

    private void acceptGenerationRun(GenerationRun run) {
        SwingUtilities.invokeLater(() -> {
            datasource.graph.GraphDiscoveryState previousGraph = graphDiscoveryLedger;
            // Closes the runtime this run supersedes — unless the incoming run carries it,
            // which is how forgetFetchedDeclaration replaces a run without shutting one
            // that is still in use.
            replaceGenerationRun(run);

            if (run != null) {
                datasource.graph.GraphDiscoveryState observed =
                        wikidata.explore.generation.WikidataGraphDiscoveryState.compute(
                                run.modelSnapshot(), run.dynamicObjects());
                graphDiscoveryLedger = previousGraph.reconcile(
                        observed,
                        wikidata.explore.generation.WikidataGraphExpansionPlan
                                .compile(run.modelSnapshot()).edges(),
                        run.quality().complete());
                // Graph state is presentation metadata, like the QID chip: show it in
                // card titles without inventing stored fields or affecting search/sort.
                instancesPanel.cardDecorator(new GraphCoverageCardDecorator(
                        graphDiscoveryState(),
                        wikidata.explore.generation.WikidataGraphExpansionPlan
                                .compile(run.modelSnapshot()).edges()));
                instancesPanel.accept(run.objectResult());
                // Generation runs on a COPY of the model, so a descriptive vocabulary
                // built from the loaded data (e.g. NomineeType, WorkGenre) lands on
                // that copy and is otherwise lost. Fold the built values back into the
                // live model so this session shows them. Descriptive vocabularies are
                // REFRESHED, not filled-if-empty — they are derived, so a stale value
                // must not linger; an authored constraint vocabulary (OscarCategories)
                // is not a descriptive target and is never touched.
                int filledVocabs = mergeBuiltVocabularies(run.modelSnapshot());
                if (filledVocabs > 0) {
                    modelChanged();
                    logWindow.info(filledVocabs + " vocabulary(ies) filled from the "
                                           + "generated data — use \"Save domain\" to persist them.");
                }
                // Do NOT auto-save: generating used to silently overwrite the
                // project snapshot (a greekmyth run clobbered constellations).
                // The run is held in memory + shown here; persisting is explicit
                // and confirmed via "Save domain".
                logWindow.info("Generated " + run.size()
                                       + " objects (in memory — use \"Save domain\" to persist "
                                       + "to " + snapshotFile().getName() + ").");
                reportNameCollisions(run);
                updateGraphFrontierButton();
                // What the declared expectations found. Silent when they all held, so a
                // clean run says nothing and a gap is the only thing that speaks up.
                // The results window is gone the moment it is accepted, taking every
                // rule bucket with it. The run still HOLDS what it did — so say it here,
                // where it stays readable and is saved with the run log.
                logWindow.info(wikidata.explore.generation.RunAudits.report(run));
            } else {
                instancesPanel.clear();
                updateGraphFrontierButton();
            }
        });
    }

    private datasource.graph.GraphDiscoveryState graphDiscoveryState() {
        return lastRun == null
                ? datasource.graph.GraphDiscoveryState.EMPTY : graphDiscoveryLedger;
    }

    /**
     * Every edge with a reviewable frontier. A statement pattern is persisted in the
     * ledger; a field edge is derived from the model, so both are asked for here and
     * the two callers cannot disagree about which edges exist.
     */
    private java.util.List<datasource.graph.GraphEdgeDefinition> reviewableEdges() {
        if (lastRun == null) return java.util.List.of();
        return wikidata.explore.generation.WikidataGraphExpansionPlan
                .compile(projectModel).edges();
    }

    private void updateGraphFrontierButton() {
        datasource.graph.GraphDiscoveryState state = graphDiscoveryState();
        int count = reviewableEdges().stream().mapToInt(
                edge -> state.frontier(edge).size()).sum();
        graphFrontierButton.setText(count == 0
                ? "Graph frontier" : "Graph frontier (" + count + ")");
        graphFrontierButton.setEnabled(lastRun != null && count > 0);
    }

    private void showGraphFrontier() {
        if (lastRun == null || processRunner.isRunning()
                || querySession.runner().isRunning()) return;
        try {
            sourceWorkbench.applyEdits();
        } catch (Exception failure) {
            reportGenerationError(failure);
            return;
        }
        GraphFrontierWorkflowAction action = new GraphFrontierWorkflowAction(
                graphDiscoveryState(), reviewableEdges(), lastRun.dynamicObjects(),
                this::applyGraphFrontier, generateDomainButton::doClick);
        process.swing.workflow.SwingProcessWorkflow.start(this, processRunner, action);
    }

    private void applyGraphFrontier(
            java.util.List<GraphFrontierWorkflowAction.Decision> decisions) {
        if (decisions.isEmpty()) return;
        int queued = 0;
        for (GraphFrontierWorkflowAction.Decision decision : decisions) {
            if (WikidataIds.isQid(decision.qid())) {
                // Resolved against the edges offered for review, not the persisted
                // patterns: a field edge is derived from the model and is not in there,
                // so an id lookup could only ever find a statement pattern.
                datasource.graph.GraphEdgeDefinition edge = reviewableEdges().stream()
                        .filter(candidate -> candidate.id().equals(decision.patternId()))
                        .findFirst().orElse(null);
                datasource.graph.GraphDiscoveryState next = graphDiscoveryLedger.queue(
                        edge, datasource.EntityRef.wikidata(decision.qid()));
                if (next != graphDiscoveryLedger) {
                    graphDiscoveryLedger = next;
                    queued++;
                }
            }
        }
        instancesPanel.cardDecorator(new GraphCoverageCardDecorator(
                graphDiscoveryLedger, reviewableEdges()));
        updateGraphFrontierButton();
        pendingGenerationReason = "Expand " + decisions.size()
                + " graph-frontier node(s)";
        logWindow.info("Queued " + queued
                + " graph-frontier node(s) in the snapshot ledger"
                + (queued == decisions.size() ? "" : " ("
                + (decisions.size() - queued) + " already queued)")
                + "; opening Generate domain preview.");
    }

    /**
     * The single assignment boundary for the active run. A run owns its generated runtime,
     * so replacing or clearing it must first release whatever the replacement does not carry.
     */
    private void replaceGenerationRun(GenerationRun next) {
        lastRun = wikidata.explore.generation.GenerationRuns.handOver(lastRun, next);
        sourceWorkbench.refreshDomainOverview();
    }

    /** Fold vocabularies BUILT during generation (from a referenced field's loaded
     *  values) back into the live model, so this session shows them. Descriptive vocabs
     *  (NomineeType, WorkGenre) are REFRESHED — they are derived, so a stale value must
     *  not linger — and created locally if generation invented them. An authored
     *  constraint vocab (OscarCategories) is untouched because it is not a descriptive
     *  target, not because anything checks whether it already has values.
     *  @return how many vocabularies were refreshed/created. */
    private int mergeBuiltVocabularies(GeneratedProjectModel built) {
        return mergeBuiltVocabularies(built, projectModel);
    }

    /** Reflect the generation-derived DESCRIPTIVE vocabularies (NomineeType, WorkGenre)
     *  into the live model so this session shows them. They are derived, so we REFRESH
     *  (overwrite) — a stale value must not linger — but only the descriptive targets;
     *  an authored constraint vocabulary (OscarCategories) is never touched. The values
     *  are not persisted: {@code saveDomain} strips them and load re-derives from the
     *  snapshot. Package-private + static so it is unit-testable without a live frame. */
    static int mergeBuiltVocabularies(
            GeneratedProjectModel built, GeneratedProjectModel into) {
        if (built == null || into == null) {
            return 0;
        }
        java.util.Set<String> descriptive =
                wikidata.explore.transform.DescriptiveVocabularyBuild.targets(into);
        int filled = 0;
        for (String name : descriptive) {
            if (!(built.findSelection(name) instanceof VocabularySelection src)) {
                continue;
            }
            java.util.List<String> values = src.valueQids() == null
                    ? java.util.List.of() : src.valueQids();
            Selection existing = into.findSelection(name);
            if (existing instanceof VocabularySelection target) {
                target.valueQids(new java.util.ArrayList<>(values));   // refresh
            } else if (existing == null) {
                VocabularySelection created = new VocabularySelection(name);
                created.valueQids(new java.util.ArrayList<>(values));
                into.addSelection(created);
            } else {
                continue;
            }
            filled++;
        }
        return filled;
    }

    // Greek myth (and history generally) has many distinct entities sharing a
    // name (5 Agenors, 5 Lycuses, …). Make that explicit after a run: list the
    // names that map to >1 QID, so the user can disambiguate or exclude.
    private void reportNameCollisions(GenerationRun run) {
        lastCollisions = java.util.List.of();
        updateNameCollisionsButton();
        if (run == null || run.dynamicObjects() == null) {
            return;
        }
        // What counts as a collision is NameCollisions'; this frame reports it.
        java.util.List<wikidata.explore.generation.NameCollisions.ClassCollisions> byClass =
                wikidata.explore.generation.NameCollisions.detect(
                        run.dynamicObjects(), run.modelSnapshot());
        if (byClass.isEmpty()) {
            return;
        }
        var ambiguity = wikidata.explore.generation.NameCollisions.Meaning.ENTITY_AMBIGUITY;
        var statement = wikidata.explore.generation.NameCollisions.Meaning.STATEMENT_REPETITION;
        var owned = wikidata.explore.generation.NameCollisions.Meaning.OWNED_REPETITION;
        java.util.List<wikidata.explore.generation.NameCollisions.ClassCollisions>
                entityClasses = wikidata.explore.generation.NameCollisions.classes(
                        byClass, ambiguity);
        java.util.List<wikidata.explore.generation.NameCollisions.Collision> collisions =
                wikidata.explore.generation.NameCollisions.flatten(entityClasses);

        // Structured, collapsible entry — one row per colliding name (name ×count),
        // biggest first — instead of one blob listing every QID (which buried the
        // rest of the log). The QIDs themselves are inspectable, clickable, via the
        // "Name collisions" button; the row detail keeps them collapsed.
        // One row per class, then that class's worst labels, under one entry per
        // MEANING. Three entries rather than one because the counts are of different
        // orders and answer different questions: on Oscars, 2499 Nominations repeat a
        // nominee's name and 145 entities are genuinely ambiguous, and in a single list
        // the first buries the second. Each is still reported — a derived label that
        // repeats says something about the rule that derived it.
        reportSharedLabels(entityClasses,
                "ambiguous entity name(s)", "distinct datasource entities",
                "entities", true);
        reportSharedLabels(wikidata.explore.generation.NameCollisions.classes(byClass, statement),
                "repeated statement label(s)",
                "statement records repeating a label their display-name rule takes "
                        + "from a participant",
                "statement records", false);
        reportSharedLabels(wikidata.explore.generation.NameCollisions.classes(byClass, owned),
                "repeated owned-part label(s)",
                "owned parts whose display name is inherited from their owner",
                "owned parts", false);

        // Map each generated instance by its QID, so a colliding entry links to
        // the actual entity used in the instances (click through), falling back
        // to a Source (QID + wiki link) for QIDs that weren't materialized.
        java.util.Map<String, objectview.Viewable> byQid = new java.util.HashMap<>();
        if (run.instances() != null) {
            for (objectview.Viewable q : run.instances()) {
                if (q != null && q.getIdentifier() != null) {
                    byQid.putIfAbsent(q.getIdentifier(), q);
                }
            }
        }

        java.util.List<NameCollision> cards = new java.util.ArrayList<>();
        for (var collision : collisions) {
            java.util.List<objectview.Viewable> entities = new java.util.ArrayList<>();
            for (String qid : collision.ids()) {
                objectview.Viewable used = byQid.get(qid);
                entities.add(used != null ? used : new quiz.source.WikidataSource(qid));
            }
            cards.add(new NameCollision(collision.name(), entities));
        }
        lastCollisions = cards;
        updateNameCollisionsButton();
    }

    private void reportSharedLabels(
            java.util.List<wikidata.explore.generation.NameCollisions.ClassCollisions> classes,
            String summaryNoun, String explanation, String instanceNoun,
            boolean inspectableAsCollision) {
        if (classes == null || classes.isEmpty()) return;
        int labels = wikidata.explore.generation.NameCollisions.flatten(classes).size();
        int instances = wikidata.explore.generation.NameCollisions.instanceCount(classes);
        java.util.List<wikidata.explore.query.swing.WorkflowLogWindow.Row> rows =
                new java.util.ArrayList<>();
        int perClass = 8;
        for (var clazz : classes) {
            rows.add(new wikidata.explore.query.swing.WorkflowLogWindow.Row(
                    clazz.className(), clazz.size() + " shared label(s) — most repeated "
                            + clazz.worst() + " times", ""));
            int shown = 0;
            for (var shared : clazz.collisions()) {
                if (shown++ >= perClass) {
                    rows.add(new wikidata.explore.query.swing.WorkflowLogWindow.Row(
                            "    … and " + (clazz.size() - perClass) + " more in "
                                    + clazz.className(),
                            inspectableAsCollision
                                    ? "open \"Name collisions\" to see all" : "", ""));
                    break;
                }
                rows.add(new wikidata.explore.query.swing.WorkflowLogWindow.Row(
                        "    " + shared.name(),
                        shared.size() + " " + instanceNoun, ""));
            }
        }
        logWindow.structuredEntry(
                labels + " " + summaryNoun + " across " + classes.size()
                        + " class(es) — " + instances + " instances involved",
                explanation + (inspectableAsCollision
                        ? "; open \"Name collisions (" + labels + ")\" to inspect." : "."),
                rows);
    }

    private void updateNameCollisionsButton() {
        int n = lastCollisions.size();
        nameCollisionsButton.setText(
                n == 0 ? "Name collisions" : "Name collisions (" + n + ")");
        nameCollisionsButton.setEnabled(n > 0);
    }

    private void showNameCollisions() {
        if (lastCollisions.isEmpty()) {
            return;
        }
        CardListView view = nameCollisionsView == null
                ? (nameCollisionsView = new CardListView()) : nameCollisionsView;
        // Share the instances panel's render context so clicking a colliding
        // entity navigates to (focuses + scrolls to) its card in the instances
        // window instead of opening a detached copy.
        RenderContext shared = instancesPanel.activeRenderContext();
        if (shared != null) {
            view.setRenderContext(shared);
            view.setInPlaceNavigation(true);
        }
        view.setViewables(lastCollisions);
        view.createCardsPanel(1);
        // The card window reuses itself, search bar and all. Rebuilding that here to
        // gain reuse would be a second copy of it in the app, and the two would drift
        // apart the moment the shared window gains anything.
        view.show("Name collisions", 1);
    }

    /** Information buttons are idempotent: reveal and focus their retained window
     * instead of silently creating another copy. Also restores a minimized frame. */
    private static void showAndFocus(JFrame window) {
        objectview.utils.swing.SwingWindowActivation.showAndFocus(window);
    }

    // Returns a human-readable reason the class can't be populated, or null if it
    // can. A class is generatable with a membership target (sourceQid), extra type
    // QIDs, or an explicit seed-QID set — same rule as GenerateDomainQuery. The
    // message is specific: a set relation with a blank target is the common trap
    // (e.g. P1411 "nominated for" with no award to point at).
    private String membershipProblem(GeneratedClassModel c) {
        if (c == null) {
            return "No class selected.";
        }
        // A statement class is populated by reification (its subjects come from a
        // source class or are discovered), not by a membership query — so it needs
        // no membership target. GenerateDomainQuery reifies it regardless.
        if (c.reifiesStatements()) {
            return null;
        }
        if (wikidata.explore.model.MembershipPattern.of(c, projectModel)
                == wikidata.explore.model.MembershipPattern.OWNED_COMPONENT) {
            return "Class \"" + c.className() + "\" is produced through "
                    + wikidata.explore.model.MembershipPattern.describe(c, projectModel)
                    + ". Use Generate domain; it has no independent query.";
        }
        var m = c.effectiveInstanceMapping(projectModel);
        boolean hasTarget = m != null && !m.sourceQid().isBlank();
        boolean hasExtraTypes = m != null && !m.additionalTypeQids().isEmpty();
        boolean hasSeeds = !c.seedQids().isEmpty();
        if (hasTarget || hasExtraTypes || hasSeeds) {
            return null;
        }
        String rel = m == null || m.propertyPid().isBlank() ? "P31" : m.propertyPid();
        if (!rel.equals("P31")) {
            return "Class \"" + c.className() + "\" has relation property " + rel
                    + " but no target. Set the \"Relation target\" (the entity the "
                    + "relation points to — e.g. the award), or add Seed QIDs.";
        }
        return "Class \"" + c.className() + "\" has no membership type (Wikidata "
                + "type/class) and no Seed QIDs — nothing to generate. Set a "
                + "type/class QID (or a relation + target), or add Seed QIDs.";
    }

    private GeneratedClassModel classByName(String name) {
        if (name == null) {
            return null;
        }
        for (GeneratedClassModel c : projectModel.classes()) {
            if (c != null && name.equals(c.className())) {
                return c;
            }
        }
        return null;
    }

    private void warnNothingToGenerate(String message) {
        logWindow.info(message);
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this, message, "Nothing to generate",
                JOptionPane.INFORMATION_MESSAGE));
    }

    // Guided build-steps for the active class: the model-builder explaining itself.
    /**
     * The model changed — re-render EVERY view of it. The class tree is only one:
     * the model graph and the build guide draw from the same model and silently
     * went stale whenever a caller remembered the tree and forgot them, which is
     * why a domain switch or a generation run left them describing the previous
     * model. One call, so a new mutation site cannot refresh some views and not
     * others. Selection changes are NOT model changes and stay separate.
     */
    /** Drops one declaration from the run's fetch record, so the next Enrich loads that
     *  field again. The record is what keeps Enrich cheap — a field already fetched is
     *  skipped even where Wikidata had no answer — so refreshing VALUES is an explicit
     *  act, per field, rather than a flag on the whole run. */
    private void forgetFetchedDeclaration(String declarationKey) {
        if (lastRun == null || declarationKey == null || declarationKey.isBlank()) {
            return;
        }
        List<wikidata.explore.extract.LoadedDeclaration> kept =
                lastRun.loadedDeclarations().stream()
                        .filter(d -> !declarationKey.equals(d.key()))
                        .toList();
        if (kept.size() == lastRun.loadedDeclarations().size()) {
            logWindow.info(declarationKey + " has not been fetched in this run — "
                    + "the next Enrich loads it anyway.");
            return;
        }
        // Forgetting a declaration changes what the NEXT enrich will fetch. It does
        // not un-run the rules this run already ran, so their audits carry over —
        // rebuilding without them would quietly report that nothing had been evaluated.
        replaceGenerationRun(new GenerationRun(
                lastRun.modelSnapshot(), lastRun.depth(), lastRun.plan(),
                lastRun.dynamicObjects(), lastRun.runtime(), lastRun.instances(),
                lastRun.remapState(), kept, lastRun.quality(), lastRun.fieldCoverage(),
                lastRun.selfReferenceAudit(), lastRun.ownedCompositionAudit(),
                lastRun.kindClassificationAudit(), lastRun.projectionAudit()));
        logWindow.info("Will re-fetch " + declarationKey + " on the next Enrich.");
    }

    /**
     * Copy takes the class in front of the reader. There is nothing to choose: the source
     * is where they are, which is what makes paste — not copy — the operation that asks.
     */
    private void copyClassConfiguration() {
        sourceWorkbench.applyEdits();
        GeneratedClassModel selected = classModelPanel.selectedClassOrRoot();
        if (selected == null) return;
        if (selected.isImported()) {
            JOptionPane.showMessageDialog(this,
                    selected.className() + " is imported from " + selected.importedFrom()
                            + ".\n\nCopy it from that model, so the copy is of the class "
                            + "as its owner defines it.",
                    "Copy class", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        classClipboard.copy(projectModel, selected.className());
        classModelPanel.refresh();
        JOptionPane.showMessageDialog(this,
                selected.className() + " copied from " + projectModel.name()
                        + ".\n\nOpen the domain or model to put it in, then Paste class.",
                "Copy class", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Paste puts the copied class where the reader now is. */
    private void pasteClassConfiguration() {
        if (!classClipboard.canPasteInto(projectModel)) {
            JOptionPane.showMessageDialog(this,
                    classClipboard.refusalFor(projectModel),
                    "Paste class", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        bringIn(classClipboard.snapshot(), classClipboard.className(),
                classClipboard.sourceName(), ClassImportPlan.Ownership.COPY);
    }

    private void importClassConfiguration() {
        boolean importing = true;
        try {
            sourceWorkbench.applyEdits();
            java.util.List<String> sources =
                    storage.importSourcesFor(projectModel.name());
            if (sources.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "No saved model is available to import from.\n\n"
                                + "An imported class stays owned by the model it comes "
                                + "from. Create a model and put the classes there first "
                                + "— copy and paste them in from a domain.",
                        "Import class", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            java.util.LinkedHashMap<String, String> labelled =
                    new java.util.LinkedHashMap<>();
            for (String name : sources) labelled.put(name + "  (model)", name);
            Object[] sourceChoices = labelled.keySet().toArray();
            String chosen = (String) JOptionPane.showInputDialog(
                    this, "Import a class from:", "Import class",
                    JOptionPane.PLAIN_MESSAGE, null, sourceChoices, sourceChoices[0]);
            if (chosen == null) return;
            String domain = labelled.get(chosen);
            if (domain == null) return;

            File sourceFile = storage.modelFileOf(domain);
            GeneratedProjectModel source = new GeneratedProjectModelStore().load(sourceFile);
            java.util.List<String> classNames = source.classes().stream()
                    .map(GeneratedClassModel::className).toList();
            String className = (String) JOptionPane.showInputDialog(
                    this, "Class:", "Import from " + domain,
                    JOptionPane.PLAIN_MESSAGE, null, classNames.toArray(),
                    classNames.getFirst());
            if (className == null) return;
            bringIn(source, className, domain, ClassImportPlan.Ownership.IMPORT);
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                    "Could not import class", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Brings one class from {@code source} into this project — the half paste and import
     * share. What differs is where the source came from and whether the result is this
     * project's own, and both are settled before this is called.
     */
    private void bringIn(GeneratedProjectModel source, String className,
            String domain, ClassImportPlan.Ownership ownership) {
        boolean importing = ownership == ClassImportPlan.Ownership.IMPORT;
        try {
            sourceWorkbench.applyEdits();
            ClassImportPlan plan = ClassImportPlan.of(source, projectModel, className);
            DefaultListModel<String> dependencyModel = new DefaultListModel<>();
            plan.dependencyClassNames().forEach(dependencyModel::addElement);
            JList<String> dependencies = new JList<>(dependencyModel);
            dependencies.setVisibleRowCount(Math.min(8,
                    Math.max(2, dependencyModel.getSize())));
            dependencies.setSelectionMode(
                    ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            if (!dependencyModel.isEmpty()) {
                dependencies.setSelectionInterval(0, dependencyModel.size() - 1);
                dependencies.setEnabled(!importing);
            }

            JTextArea preview = new JTextArea(8, 52);
            preview.setEditable(false);
            preview.setLineWrap(true);
            preview.setWrapStyleWord(true);
            preview.setText((importing ? "Import " : "Paste ") + className + " from "
                    + domain + " into " + projectModel.name() + ".\n\n"
                    + (importing
                            ? "The class stays owned by " + domain
                                    + ". It is shown and used here, and edited there.\n\n"
                            : "The copy belongs to " + projectModel.name()
                                    + " and is edited here like any other class.\n\n")
                    + "Class configuration: identity, display name, inheritance, "
                    + "fields and source bindings.\n"
                    + "Supporting declarations: " + plan.selections().size()
                    + " selection(s), " + plan.kindRules().size()
                    + " entity-kind rule(s).\n"
                    + "\nGenerated instances, observed vocabulary values, counts "
                    + "and curation are not copied.");

            JPanel choices = new JPanel();
            choices.setLayout(new BoxLayout(choices, BoxLayout.Y_AXIS));
            // First, and outside the preview. Inside it the warning sat below a screenful
            // of description in a scrolling area, so the one question that loses work was
            // the one thing a reader had to go looking for.
            if (!plan.conflicts().isEmpty()) {
                JLabel warning = new JLabel("<html><b>"
                        + (importing
                                ? "Already here, so this import is refused: "
                                : "Already here and REPLACED, losing what is here now: ")
                        + String.join(", ", plan.conflicts())
                        + "</b><br>"
                        + (importing
                                ? "Rename or remove them first."
                                : "Cancel and rename it first if you want to keep both.")
                        + "</html>");
                warning.setForeground(new java.awt.Color(150, 40, 40));
                warning.setBorder(BorderFactory.createEmptyBorder(2, 2, 8, 2));
                warning.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
                choices.add(warning);
            }
            JScrollPane previewScroll = new JScrollPane(preview);
            previewScroll.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            choices.add(previewScroll);
            if (!dependencyModel.isEmpty()) {
                choices.add(Box.createVerticalStrut(8));
                choices.add(new JLabel(importing
                        ? "Required dependent classes:"
                        : "Dependent classes (selected = copy):"));
                choices.add(new JScrollPane(dependencies));
            }
            // Replace or cancel, asked once. A second control offering to keep the
            // existing declaration asked the same question a different way, and the
            // answer it added is better reached by renaming what is here first.
            int accepted = JOptionPane.showConfirmDialog(this, choices,
                    importing ? "Import class" : "Paste class",
                    JOptionPane.OK_CANCEL_OPTION,
                    plan.conflicts().isEmpty()
                            ? JOptionPane.PLAIN_MESSAGE : JOptionPane.WARNING_MESSAGE);
            if (accepted != JOptionPane.OK_OPTION) return;

            java.util.LinkedHashSet<String> selected =
                    new java.util.LinkedHashSet<>(dependencies.getSelectedValuesList());
            selected.add(className);
            java.util.List<GeneratedClassModel> imported =
                    plan.apply(selected, ownership);
            modelChanged();
            GeneratedClassModel copied = projectModel.findClass(className);
            if (copied != null) classModelPanel.selectClass(copied);
            sourceWorkbench.edit(copied);
            JOptionPane.showMessageDialog(this,
                    imported.isEmpty()
                            ? "The selected target declarations were reused."
                            : (importing ? "Imported " : "Pasted ") + imported.size()
                                    + " class configuration(s): "
                                    + imported.stream().map(GeneratedClassModel::className)
                                            .collect(java.util.stream.Collectors.joining(", ")),
                    importing ? "Import class" : "Paste class",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception failure) {
            JOptionPane.showMessageDialog(this,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                    importing ? "Could not import class" : "Could not paste class",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void modelChanged() {
        classModelPanel.refresh();
        sourceWorkbench.refreshDomainOverview();
        if (graphWindow != null && graphWindow.isVisible()) {
            graphPanel.setModel(projectModel);
        }
        if (guidePanel != null && guideWindow != null && guideWindow.isVisible()) {
            guidePanel.refresh();
        }
    }

    private void updateConfigurationLock() {
        setConfigurationLocked(processRunner.isRunning() || querySession.runner().isRunning());
    }

    private void setConfigurationLocked(boolean locked) {
        if (configurationLocked == locked) return;
        configurationLocked = locked;
        configurationLockLabel.setVisible(locked);

        domainBox.setEnabled(!locked);
        modelBox.setEnabled(!locked);
        newDomainButton.setEnabled(!locked);
        renameDomainButton.setEnabled(!locked);
        deleteDomainButton.setEnabled(!locked);
        loadProjectButton.setEnabled(!locked);
        loadSavedButton.setEnabled(!locked);
        saveEverythingButton.setEnabled(!locked);
        depthSpinner.setEnabled(!locked);
        boolean editable = !locked;
        classModelPanel.setEditingEnabled(editable);
        sourceWorkbench.setEditingEnabled(editable);
        showEntityKindsButton.setEnabled(!locked);
        showGuideButton.setEnabled(!locked);

        generateButton.setEnabled(!locked);
        generateDomainButton.setEnabled(!locked);
        remapButton.setEnabled(!locked && lastRun != null);
        enrichButton.setEnabled(!locked && lastRun != null);
        // Read-only, not switched off: a disabled JFrame ignores every event including
        // its own close button, so a window opened before a 25-minute generation could
        // neither be used nor dismissed.
        if (entityKindsWindow != null) {
            EditableComponents.setEditable(entityKindsWindow.getContentPane(), !locked);
        }
        if (guideWindow != null) {
            EditableComponents.setEditable(guideWindow.getContentPane(), !locked);
        }

        revalidate();
        repaint();
    }

    private void refreshProjectKindUi() {
        boolean model = projectModel.isModel();
        newDomainButton.setText("New…");
        renameDomainButton.setText(model ? "Rename model" : "Rename domain");
        deleteDomainButton.setText(model ? "Delete model" : "Delete domain");
        saveEverythingButton.setText(model ? "Save model" : "Save domain");
        saveEverythingButton.setToolTipText(model
                ? "Save this model's reusable configuration. Models have no instances."
                : "Save this domain's configuration and generated instances together.");
        runSection.setVisible(projectModel.supportsExecution());
        loadSavedButton.setVisible(projectModel.supportsExecution());
        boolean editable = !configurationLocked;
        renameDomainButton.setEnabled(editable);
        classModelPanel.setEditingEnabled(editable);
        sourceWorkbench.setEditingEnabled(editable);
        revalidate();
        repaint();
    }

    private void showGuide() {
        if (guideWindow == null) {
            guidePanel = new ModelingGuidePanel(this::guideContext);
            guideWindow = new JFrame("Build guide");
            guideWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
            guideWindow.setContentPane(guidePanel);
            guideWindow.setSize(460, 460);
            guideWindow.setLocationRelativeTo(this);
        }
        guidePanel.refresh();
        showAndFocus(guideWindow);
    }

    // The context follows the exact tree selection: domain, class or field.
    private wikidata.explore.advisor.DecisionContext guideContext() {
        Object selected = classModelPanel.selectedUserObject();
        GeneratedFieldModel field = selected instanceof GeneratedFieldModel f ? f : null;
        GeneratedClassModel active = selected instanceof GeneratedClassModel c
                ? c
                : field == null ? null : activeClass();
        return new wikidata.explore.advisor.DecisionContext(
                projectModel, active, field,
                new wikidata.explore.transform.TransformConfig());
    }

    // Surfaces a generation failure visibly (a dialog plus the log) rather than
    // only writing to a log window the user may not have open, and offers a
    // hint for the failures we recognize.
    private void reportGenerationError(Throwable ex) {
        String message = ex == null ? "Unknown error" : ex.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.toString();
        }

        String hint = hintFor(message);

        logWindow.info("Generate failed: " + message
                               + (hint.isBlank() ? "" : "\n" + hint));

        String body = message + (hint.isBlank() ? "" : "\n\n" + hint);

        SwingUtilities.invokeLater(() ->
                                           JOptionPane.showMessageDialog(
                                                   this, body, "Generation failed",
                                                   JOptionPane.ERROR_MESSAGE));
    }

    private static String hintFor(String message) {
        String m = message == null ? "" : message.toLowerCase();

        if (m.contains("can not set") && m.contains("arraylist")) {
            return "Hint: a single-valued field received several values from "
                    + "Wikidata. Set that field's cardinality to Collection to "
                    + "keep them all (otherwise only the first is used).";
        }
        if (m.contains("cannot find symbol")
                || m.contains("compilation")
                || m.contains("compile")) {
            return "Hint: the generated class did not compile. Use "
                    + "'Show generated source' to inspect it; an entity field "
                    + "usually needs a valid object type (or none -> Viewable).";
        }
        if (m.contains("timeout") || m.contains("timed out")) {
            return "Hint: the SPARQL endpoint timed out. Lower the depth/limit, "
                    + "or reduce the number of fields and retry.";
        }
        return "";
    }

    // Loads a previously saved snapshot of dynamic objects and maps them
    // through the current model -- the same materialize path as Generate, but
    // without re-querying Wikidata.
    // On startup, restore the saved project model (config) if one exists, so
    // edits persist across restarts rather than reverting to the demo. Best
    // effort: a missing/corrupt file just leaves the demo in place.
    // ------------------------------------------------------------------
    // Domains: the project as a whole is one domain (name + its classes),
    // keyed by name. The registry is the list of domains; New/Rename/Delete
    // manage them and "Save domain" persists the selected one.
    // ------------------------------------------------------------------

    // Repopulate the separate domain/model selectors from storage plus the current
    // (possibly unsaved) project. Guarded so neither selector fires a switch while
    // both are being rebuilt.
    private void refreshDomainBox() {
        updatingDomainBox = true;
        try {
            Object previousDomain = domainBox.getSelectedItem();
            Object previousModel = modelBox.getSelectedItem();
            java.util.LinkedHashSet<String> domains =
                    new java.util.LinkedHashSet<>(storage.domainKindNames());
            java.util.LinkedHashSet<String> models =
                    new java.util.LinkedHashSet<>(storage.modelKindNames());
            (projectModel.isModel() ? models : domains).add(projectModel.name());
            domainBox.removeAllItems();
            modelBox.removeAllItems();
            domains.forEach(domainBox::addItem);
            models.forEach(modelBox::addItem);
            // Rebuilding a JComboBox selects its first entry. Keep that meaningful
            // choice in the other-kind selector rather than blanking it: Domain and
            // Model are two usable project pickers, not one active picker plus an empty
            // category. The currently open project is selected in its own picker.
            if (projectModel.isModel()) {
                modelBox.setSelectedItem(projectModel.name());
                if (previousDomain != null && domains.contains(previousDomain.toString())) {
                    domainBox.setSelectedItem(previousDomain);
                }
            } else {
                domainBox.setSelectedItem(projectModel.name());
                if (previousModel != null && models.contains(previousModel.toString())) {
                    modelBox.setSelectedItem(previousModel);
                }
            }
        } finally {
            updatingDomainBox = false;
        }
        logWindow.saveContext(projectModel.name(), projectDataDir().toPath());
    }

    private quiz.DatasetRegistry.Dataset findDataset(String name) {
        return storage.find(name);
    }

    private File domainModelFile(String name) {
        return storage.modelFileOf(name);
    }

    /**
     * Whether this project differs from the file it came from.
     *
     * <p>An unknown signature is not a claim that nothing changed: a model that cannot be
     * compiled has none, and a draft has no file to differ from. Both answer "yes, ask" —
     * the safe direction, since the cost of asking is a dialog and the cost of not asking
     * is lost work.
     */
    private boolean hasUnsavedChanges() {
        String saved = savedSignature;
        String now = modelSignature(projectModel);
        return saved.isBlank() || now.isBlank() || !now.equals(saved);
    }

    /** This project is now exactly what {@code file} holds. */
    private void markSaved(File file) {
        openModelFile = file;
        savedSignature = file == null ? "" : modelSignature(projectModel);
    }

    /** The folder this project lives in, or null for a draft that has no home yet. */
    private File openDomainDir() {
        if (openModelFile != null && openModelFile.getParentFile() != null) {
            return openModelFile.getParentFile();
        }
        File model = domainModelFile(projectModel.name());
        return model == null ? null : model.getParentFile();
    }

    private boolean confirmDiscardChanges(String title) {
        // Nothing to discard, nothing to ask. A dialog that always appears is one a
        // reader learns to dismiss without reading, which is exactly when it needed to
        // be read.
        if (!hasUnsavedChanges()) return true;
        int c = JOptionPane.showConfirmDialog(
                this,
                "Discard any unsaved changes to \"" + projectModel.name()
                        + "\" and continue?\n"
                        + "(Use \"Save domain\" first to keep them.)",
                title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return c == JOptionPane.OK_OPTION;
    }

    // Switch to another domain selected in the combo: confirm, then load its
    // saved model. Reverts the combo selection if it can't proceed.
    private void switchToDomain(String name) {
        File model = domainModelFile(name);
        if (model == null || !model.isFile()) {
            JOptionPane.showMessageDialog(this,
                                          "No saved model for domain \"" + name + "\" at\n"
                                                  + (model == null ? "(unknown)" : model.getPath()),
                                          "Cannot switch domain", JOptionPane.WARNING_MESSAGE);
            refreshDomainBox();
            return;
        }
        if (!confirmDiscardChanges("Switch domain")) {
            refreshDomainBox();
            return;
        }
        doLoadDomain(model);
        refreshDomainBox();
    }

    // Loads a domain's model in place (no confirm); shared by switch and the
    // post-delete fallback.
    private boolean doLoadDomain(File model) {
        try {
            GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(model);
            datasource.graph.GraphDiscoveryState ledger =
                    graphDiscoveryBeside(model);
            // The caller has already confirmed that pending changes may be discarded.
            // Detach the old-domain editors BEFORE replacing the shared model; the tree
            // refresh caused by modelChanged() must not flush those controls into the
            // newly loaded domain.
            sourceWorkbench.abandonEdits();
            projectModel.copyContentsFrom(loaded);
            replaceGenerationRun(null);
            graphDiscoveryLedger = ledger;
            instancesPanel.clear();
            modelChanged();
            classModelPanel.selectClass(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            refreshProjectKindUi();
            // Last, after the selection and refreshes: those can announce a change, and
            // a load that marked itself saved too early would then look edited.
            markSaved(model);
            rememberCurrentDomain();
            logWindow.info("Loaded " + (projectModel.isModel() ? "model" : "domain")
                    + " \"" + projectModel.name() + "\".");
            return true;
        } catch (Exception ex) {
            reportGenerationError(ex);
            return false;
        }
    }

    private void newProject() {
        JTextField nameField = new JTextField(22);
        JComboBox<GeneratedProjectModel.ProjectKind> kind = new JComboBox<>(
                GeneratedProjectModel.ProjectKind.values());
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Name:"));
        form.add(nameField);
        form.add(new JLabel("Kind:"));
        form.add(kind);
        if (JOptionPane.showConfirmDialog(this, form, "New domain or model",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                != JOptionPane.OK_OPTION) return;
        String name = nameField.getText().trim();
        if (name.isBlank()) {
            return;
        }
        String requestedKey = dataset.DomainStorage.key(name);
        String existingName = storage.modelBackedNames().stream()
                .filter(candidate -> dataset.DomainStorage.key(candidate).equals(requestedKey))
                .findFirst().orElse(null);
        if (existingName != null) {
            int c = JOptionPane.showConfirmDialog(this,
                                                  "A project \"" + existingName
                                                          + "\" already uses this name. Switch to it?",
                                                  "Project exists", JOptionPane.OK_CANCEL_OPTION);
            if (c == JOptionPane.OK_OPTION) {
                switchToDomain(existingName);
            } else {
                refreshDomainBox();
            }
            return;
        }
        if (!confirmDiscardChanges("New project")) {
            refreshDomainBox();
            return;
        }

        GeneratedProjectModel fresh = new GeneratedProjectModel();
        fresh.name(name);
        fresh.projectKind((GeneratedProjectModel.ProjectKind) kind.getSelectedItem());
        // Start with one neutrally-named root class the user then configures.
        fresh.rootClass().className(
                GeneratedViewableSourceGenerator.sanitizeClassName(name));
        projectModel.copyContentsFrom(fresh);
        replaceGenerationRun(null);
        graphDiscoveryLedger = datasource.graph.GraphDiscoveryState.EMPTY;
        instancesPanel.clear();
        modelChanged();
        classModelPanel.selectClass(projectModel.rootClass());
        syncDepthSpinnerToActiveClass();
        refreshDomainBox();
        refreshProjectKindUi();
        logWindow.info("Created new " + (fresh.isModel() ? "model" : "domain")
                + " \"" + name + "\". Configure its classes, then save it.");
    }

    // Rename in place: move the domain folder and re-key the files inside, then
    // update the registry entry (if any). Nothing destructive beyond the move.
    private void renameDomain() {
        String oldName = projectModel.name();
        String oldKey = projectKey();
        String kind = projectModel.isModel() ? "model" : "domain";
        String name = (String) JOptionPane.showInputDialog(
                this, "Rename " + kind + ":", "Rename " + kind,
                JOptionPane.QUESTION_MESSAGE, null, null, oldName);
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isBlank() || name.equals(oldName)) {
            return;
        }

        switch (storage.rename(oldName, name)) {
            case dataset.DomainStorage.Rename.FolderTaken taken -> JOptionPane.showMessageDialog(
                    this, "A project folder already exists at\n" + taken.existing().getPath()
                            + "\nChoose a different name.",
                    "Rename " + kind, JOptionPane.WARNING_MESSAGE);
            case dataset.DomainStorage.Rename.Failed failed ->
                    reportGenerationError(failed.cause());
            case dataset.DomainStorage.Rename.Done done -> {
                projectModel.name(done.name()); // file paths now use the new key
                rememberCurrentDomain();
                modelChanged();
                refreshDomainBox();
                logWindow.info("Renamed " + kind + " \"" + oldName + "\" -> \""
                        + done.name() + "\".");
            }
        }
    }

    private void deleteDomain() {
        String name = projectModel.name();
        String kind = projectModel.isModel() ? "model" : "domain";
        File dir = projectDataDir();
        int c = JOptionPane.showConfirmDialog(this,
                                              "Delete " + kind + " \"" + name + "\"?\n\n"
                                                      + "Removes it from the registry and deletes:\n"
                                                      + dir.getPath() + "\n\nThis cannot be undone.",
                                              "Delete " + kind,
                                              JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (c != JOptionPane.OK_OPTION) {
            refreshDomainBox();
            return;
        }
        try {
            storage.delete(name);
            logWindow.info("Deleted " + kind + " \"" + name + "\".");
        } catch (Exception ex) {
            reportGenerationError(ex);
        }

        // Switch to whatever else is saved — the same list the picker shows, so a model
        // is a candidate even though nothing registered it. Falling through to the demo
        // when that fails matters as much as the switch itself: the picker re-adds the
        // open project by name, so leaving the deleted one loaded puts it back in the
        // list it was just removed from.
        String next = storage.anySavedNameOtherThan(name);
        File nextFile = next == null ? null : domainModelFile(next);
        boolean switched = nextFile != null && nextFile.isFile() && doLoadDomain(nextFile);
        if (!switched) {
            projectModel.copyContentsFrom(GeneratedProjectModel.constellationDemo());
            replaceGenerationRun(null);
            graphDiscoveryLedger = datasource.graph.GraphDiscoveryState.EMPTY;
            instancesPanel.clear();
            modelChanged();
            classModelPanel.selectClass(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            // Deleting a model and landing on a domain changes the kind, and every
            // control labelled by it.
            refreshProjectKindUi();
        }
        refreshDomainBox();
    }

    private void loadSavedModelIfPresent() {
        String remembered;
        try {
            remembered = PREFERENCES.get(LAST_DOMAIN, "");
        } catch (RuntimeException unavailable) {
            remembered = "";
        }
        File f = storage.preferredModelFile(remembered, projectModel.name());
        if (f == null) {
            return;
        }
        try {
            GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(f);
            datasource.graph.GraphDiscoveryState ledger = graphDiscoveryBeside(f);
            projectModel.copyContentsFrom(loaded);
            graphDiscoveryLedger = ledger;
            modelChanged();
            syncDepthSpinnerToActiveClass();
            markSaved(f);
            rememberCurrentDomain();
        } catch (Exception ex) {
            System.err.println("Could not load saved model " + f.getPath()
                                       + ": " + ex.getMessage());
        }
    }

    /** Remember only a durable domain. An unsaved draft cannot be restored on the
     * next startup and therefore must not displace the last usable preference. */
    private void rememberCurrentDomain() {
        String name = projectModel.name();
        File model = storage.modelFileOf(name);
        if (model == null || !model.isFile()) return;
        try {
            PREFERENCES.put(LAST_DOMAIN, name);
        } catch (RuntimeException unavailable) {
            // This preference is a convenience; inability to write it must never
            // prevent closing, switching or saving a domain.
        }
    }

    // Loads a project model (config) from a chosen *.model.json, replacing the
    // current one. (Instances are reloaded separately via "Load instances".)
    // A project is one folder under data/wikidata/. A domain may also hold its
    // .ruletree/.snapshot files; a model holds configuration only. The user picks the
    // FOLDER (files are shown but not selectable — which file is a technical detail)
    // and we derive the model file.
    private File chooseDomainDir(String title) {
        JFileChooser chooser =
                new JFileChooser(new File(Constants.wikidataDataDirectory));
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        File suggested = projectDataDir();
        if (suggested.isDirectory()) {
            chooser.setSelectedFile(suggested);
        }
        return chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? chooser.getSelectedFile()
                : null;
    }

    private static File findInDir(File dir, String suffix) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] fs = dir.listFiles((d, n) -> n.endsWith(suffix));
        return fs == null || fs.length == 0 ? null : fs[0];
    }

    // The registry entry whose snapshot is this file (for the model-drift check).
    private static quiz.DatasetRegistry.Dataset datasetForSnapshot(File file) {
        try {
            File want = file.getCanonicalFile();
            for (quiz.DatasetRegistry.Dataset d
                    : quiz.DatasetRegistry.load().datasets()) {
                if (!d.snapshotPath().isBlank()
                        && new File(d.snapshotPath()).getCanonicalFile().equals(want)) {
                    return d;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void loadProject() {
        File dir = chooseDomainDir("Load project — pick its folder under data/wikidata/");
        if (dir == null) {
            return;
        }
        File model = findInDir(dir, ".model.json");
        if (model == null) {
            JOptionPane.showMessageDialog(this,
                                          "No *.model.json found in\n" + dir.getPath(),
                                          "Load project", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(model);
            datasource.graph.GraphDiscoveryState ledger =
                    graphDiscoveryBeside(model);
            projectModel.copyContentsFrom(loaded);
            replaceGenerationRun(null);
            graphDiscoveryLedger = ledger;
            instancesPanel.clear();
            modelChanged();
            classModelPanel.selectClass(projectModel.rootClass());
            syncDepthSpinnerToActiveClass();
            refreshDomainBox();
            refreshProjectKindUi();
            markSaved(model);
            logWindow.info("Loaded project configuration from " + model.getName());
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    /** The graph ledger is generated data owned by the snapshot beside the model. */
    private static datasource.graph.GraphDiscoveryState graphDiscoveryBeside(File model)
            throws java.io.IOException {
        File snapshot = model == null ? null
                : findInDir(model.getParentFile(), ".snapshot.json");
        return snapshot == null
                ? datasource.graph.GraphDiscoveryState.EMPTY
                : new WikidataDynamicObjectJsonStore().loadGraphDiscovery(snapshot);
    }

    private void loadSavedInstances() {
        // The open domain knows where its instances are — asking for the folder made
        // the reader answer a question the application had already answered, and let
        // them answer it with somebody else's domain.
        File dir = openDomainDir();
        File file = dir == null ? null : findInDir(dir, ".snapshot.json");
        if (file == null) {
            JOptionPane.showMessageDialog(this,
                                          dir == null
                                                  ? "\"" + projectModel.name() + "\" has not been "
                                                          + "saved yet, so it has no instances to load."
                                                  : "No saved instances for \"" + projectModel.name()
                                                          + "\" in\n" + dir.getPath()
                                                          + "\n(generate, then \"Save domain\")",
                                          "Load instances", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            sourceWorkbench.applyEdits();

            // Drift guard: warn if these instances were generated from a
            // different model version than the one we'll map them through.
            quiz.DatasetRegistry.Dataset ds = datasetForSnapshot(file);
            if (ds != null && wikidata.explore.generation.DomainSave.signaturesDisagree(
                    ds.modelSignature(), modelSignature(projectModel))) {
                JOptionPane.showMessageDialog(this,
                                              "These saved instances were generated from a DIFFERENT model\n"
                                                      + "version than the current model. Fields may not match —\n"
                                                      + "\"Generate class instances\" to refresh them.",
                                              "Instances may be stale", JOptionPane.WARNING_MESSAGE);
            }

            GeneratedProjectModel snapshot = projectModel.copy();
            // The snapshot also records which declarations have been FETCHED; carry them
            // onto the run so a following Enrich asks only for what is new.
            WikidataDynamicObjectJsonStore.LoadedSnapshot saved =
                    new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(file);
            graphDiscoveryLedger = saved.graphDiscovery();
            List<WikidataDynamicObject> objects = saved.objects();

            // Apply the current model's canonicalization to the loaded pool, so a
            // display-name spec set/edited after this snapshot was saved takes
            // effect on load (e.g. a Nomination shows its nominee) — no re-download.
            wikidata.explore.transform.Canonicalization.apply(snapshot, objects,
                                                              wikidata.explore.extract.GenerationLog.of(logWindow::info));

            // Derive the descriptive vocabularies (NomineeType, WorkGenre) from the
            // loaded pool — they are not persisted, so load is where they come back.
            // acceptGenerationRun then folds them into the live model for display.
            wikidata.explore.transform.DescriptiveVocabularyBuild.apply(snapshot, objects,
                                                                        wikidata.explore.extract.GenerationLog.of(logWindow::info));

            GenerationPipeline pipeline = new GenerationPipeline();
            RuleNode plan = pipeline.plan(snapshot);
            GeneratedViewableRuntime runtime = pipeline.buildRuntime(snapshot);
            List<Viewable> instances =
                    pipeline.materialize(runtime, objects);

            acceptGenerationRun(new GenerationRun(
                    snapshot, 0, plan, objects, runtime, instances,
                    null, saved.loadedDeclarations()));
            showInstancesWindow();

            logWindow.info("Loaded " + objects.size()
                                   + " saved object(s) from " + file.getName()
                                   + "; mapped " + instances.size()
                                   + " instance(s).");
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    // Make the given entity the selected class's membership type. Shared by the
    // WikiProject and Explore panels.
    private void useSourceQid(String qid, String label) {
        if (qid == null || !WikidataIds.isQid(qid)) {
            return;
        }
        GeneratedClassModel c = activeClass();
        c.instanceMapping().sourceQid(qid);
        c.instanceMapping().sourceLabel(label == null ? "" : label);
        sourceWorkbench.edit(c);
        modelChanged();
        logWindow.info("Set source of \"" + c.className() + "\" to "
                               + qid + (label == null ? "" : " (" + label + ")") + ".");
    }

    // Fill the selected class's seed-QID set (these entities become its
    // instances). Shared by the WikiProject and Explore panels.
    private void addSeedQids(List<String> qids, boolean replace) {
        GeneratedClassModel c = activeClass();
        if (replace) {
            c.seedQids().clear();
        }
        int added = 0;
        for (String qid : qids) {
            if (qid != null && WikidataIds.isQid(qid) && !c.seedQids().contains(qid)) {
                c.seedQids().add(qid);
                added++;
            }
        }
        sourceWorkbench.edit(c);
        modelChanged();
        logWindow.info((replace ? "Replaced" : "Added") + " seed QIDs on \""
                               + c.className() + "\": " + added + " (total "
                               + c.seedQids().size() + "). Empty the Wikidata type to use only "
                               + "these as instances.");
    }

    // Add explored members to the active class's membership-relation targets
    // (the "Also include types" set), so all targets live in one place — no need
    // to seed-and-cut/paste, and the main "Relation target" field can stay blank.
    private void addRelationTargets(List<String> qids) {
        GeneratedClassModel c = activeClass();
        var targets = c.instanceMapping().additionalTypeQids();
        int added = 0;
        for (String qid : qids) {
            if (qid != null && WikidataIds.isQid(qid) && !targets.contains(qid)) {
                targets.add(qid);
                added++;
            }
        }
        sourceWorkbench.edit(c);
        modelChanged();
        logWindow.info("Added " + added + " relation target(s) to \""
                               + c.className() + "\" (total " + targets.size()
                               + "). They join the membership relation (" + c.instanceMapping()
                                                                             .propertyPid() + ") as additional targets.");
    }

    // The class the toolbar actions operate on: the one selected in the class
    // tree (or the owner of a selected field), else the root class.
    private GeneratedClassModel activeClass() {
        return classModelPanel.selectedClassOrRoot();
    }

    // Reflects the active class's saved depth in the spinner without the change
    // listener writing it straight back.
    private void syncDepthSpinnerToActiveClass() {
        GeneratedClassModel c = activeClass();
        if (c == null) {
            return;
        }
        syncingDepth = true;
        try {
            depthSpinner.setValue(c.generationDepth());
        } finally {
            syncingDepth = false;
        }
    }

    // A detached copy of the project rooted at the selected class, so Generate/
    // Show source/Show rule tree target whatever class is selected — not always
    // the root. (Child-object types are derived from field configs, so the
    // single rooted class compiles and generates standalone.)
    private GeneratedProjectModel modelRootedAtSelected() {
        GeneratedProjectModel sub = projectModel.copy();
        String target = activeClass().className();
        for (GeneratedClassModel c : sub.classes()) {
            if (c.className().equals(target)) {
                if (c != sub.rootClass()) {
                    sub.rootClass(c);
                }
                break;
            }
        }
        return sub;
    }

    // Where a domain's files live is dataset.DomainStorage's business; this frame only says
    // which domain it is currently editing. The layout — "Constellations" ->
    // data/wikidata/constellations/ — is the one the web client and "Load instances" read.
    private String projectKey() { return dataset.DomainStorage.key(projectModel.name()); }

    private static String domainKey(String name) { return dataset.DomainStorage.key(name); }

    private File projectDataDir() { return storage.directory(projectModel.name()); }

    private File snapshotFile() { return storage.snapshotFile(projectModel.name()); }

    private File ruleTreeFile() { return storage.ruleTreeFile(projectModel.name()); }

    private File modelFile() { return storage.modelFile(projectModel.name()); }

    // Saves config (the editable project model), the compiled rule tree, and
    // the generated instances together into the project's data directory — the
    // instances file being exactly what the web client and "Load instances" read.
    // A model's generation signature: the rule tree it compiles to is exactly
    // what drives generation, so two models with the same signature produce the
    // same instances. Used to detect a model drifting past its saved snapshot.
    private static String modelSignature(GeneratedProjectModel model) {
        return wikidata.explore.generation.DomainSave.signature(model);
    }

    // The signature of the model the in-memory instances (lastRun) were
    // generated from, or "" if there are none.
    private String generatedInstancesSignature() {
        return lastRun != null && lastRun.modelSnapshot() != null
                ? modelSignature(lastRun.modelSnapshot())
                : "";
    }

    // The distinct class-types stamped on a set of generated objects.


    // The types already present in the saved snapshot (to detect a single-class
    // run about to overwrite a multi-class one).
    /** What the snapshot on disk holds, or nothing when there is none to read. */
    private java.util.List<WikidataDynamicObject> snapshotObjectsOnDisk() {
        try {
            return new WikidataDynamicObjectJsonStore().load(snapshotFile());
        } catch (Exception unreadable) {
            return java.util.List.of();
        }
    }

    private void saveEverything() {
        if (projectModel.isModel()) {
            saveModelOnly();
            return;
        }
        GeneratedProjectModel modelToSave = projectModel;
        boolean recoverCompletedRun = false;
        try {
            sourceWorkbench.applyEdits();
        } catch (LinkageError brokenRuntime) {
            // A live JVM can straddle an incremental/parallel compile: an editor class
            // already loaded from one build may reference a helper absent from that
            // class loader. Do not let that strand a completed 20–30 minute generation.
            // The run owns the exact immutable model that produced its objects, so it
            // can still be saved as a consistent model/rules/snapshot triple without
            // consulting the broken live editor.
            if (lastRun == null || lastRun.modelSnapshot() == null
                    || lastRun.dynamicObjects() == null
                    || lastRun.dynamicObjects().isEmpty()) {
                reportGenerationError(brokenRuntime);
                return;
            }
            int recover = JOptionPane.showConfirmDialog(this,
                    "The live configuration editor could not be applied because the "
                            + "running application has an inconsistent classpath:\n"
                            + brokenRuntime + "\n\n"
                            + "Save the completed generation using the immutable model "
                            + "snapshot that produced it?\n"
                            + "Pending editor changes will not be included.",
                    "Recover completed generation",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (recover != JOptionPane.OK_OPTION) return;
            modelToSave = lastRun.modelSnapshot();
            recoverCompletedRun = true;
        }

        // Confirm BEFORE writing — show the exact paths and what each will get,
        // so Escape actually cancels (the old dialog appeared after the files
        // were already written).
        boolean haveInstances = lastRun != null
                && lastRun.dynamicObjects() != null
                && !lastRun.dynamicObjects().isEmpty();

        // Drift guard: the snapshot we'd write came from lastRun's model; if the
        // current model has changed since, the saved instances will be stale.
        String runSig = generatedInstancesSignature();
        if (!recoverCompletedRun && haveInstances
                && wikidata.explore.generation.DomainSave.instancesWouldBeStale(runSig, modelToSave)) {
            int d = JOptionPane.showConfirmDialog(this,
                                                  "The model has changed since these instances were generated.\n"
                                                          + "The saved snapshot will be STALE (not match the saved model).\n\n"
                                                          + "Regenerate (Cancel, then \"Generate class instances\") before saving,\n"
                                                          + "or save anyway?",
                                                  "Model changed since generation",
                                                  JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (d != JOptionPane.OK_OPTION) {
                return;
            }
        }

        // Overwrite guard: a single-class run ("Generate class") must not
        // silently replace a multi-class snapshot (e.g. Episode, Labour). Warn
        // about types on disk that this run would drop. (Use "Generate domain".)
        if (haveInstances && snapshotFile().isFile()) {
            java.util.Set<String> runTypes = wikidata.explore.generation.DomainSave.stampedTypes(lastRun.dynamicObjects());
            java.util.List<String> dropped = wikidata.explore.generation.DomainSave.typesDropped(
                    lastRun.dynamicObjects(), snapshotObjectsOnDisk());
            if (!dropped.isEmpty()) {
                int d = JOptionPane.showConfirmDialog(this,
                                                      "This run produced only: "
                                                              + String.join(", ", runTypes) + ".\n"
                                                              + "Saving will OVERWRITE the snapshot and DROP these "
                                                              + "existing types: " + String.join(", ", dropped) + ".\n\n"
                                                              + "Use \"Generate domain\" to keep every class. Save anyway?",
                                                      "Overwriting a multi-class snapshot",
                                                      JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (d != JOptionPane.OK_OPTION) {
                    return;
                }
            }
        }

        String plan = "Save the domain \"" + modelToSave.name()
                + "\" — write these files?\n\n"
                + "Config:    " + modelFile().getPath() + "\n"
                + "Rule tree: " + ruleTreeFile().getPath() + "\n"
                + "Instances: " + (haveInstances
                ? lastRun.dynamicObjects().size() + " -> " + snapshotFile().getPath()
                : "(none generated yet — will be skipped)");
        int choice = JOptionPane.showConfirmDialog(
                this, plan, "Save domain",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        StringBuilder report = new StringBuilder();
        try {
            modelFile().getParentFile().mkdirs();

            // Depth is now per-class (saved on each class via the spinner change
            // listener); make sure the active class has the latest spinner value.
            GeneratedClassModel active = recoverCompletedRun ? null : activeClass();
            if (active != null) {
                active.generationDepth(((Number) depthSpinner.getValue()).intValue());
            }
            new GeneratedProjectModelStore().save(
                    wikidata.explore.generation.DomainSave.persistedModel(modelToSave), modelFile());
            rememberCurrentDomain();
            report.append("Config:    ").append(modelFile().getPath()).append('\n');

            RuleNode root = RuleTreeCompiler.compileProject(modelToSave);
            new RuleTreeSerializer().save(root, ruleTreeFile());
            report.append("Rule tree: ").append(ruleTreeFile().getPath()).append('\n');

            int n = -1;
            if (lastRun != null && lastRun.dynamicObjects() != null
                    && !lastRun.dynamicObjects().isEmpty()) {
                // The run retains the EXACT model snapshot that produced these objects.
                // Declare it into the persisted field graph so null fields and empty typed
                // collections survive without shape placeholders. Do not use projectModel:
                // the user may have explicitly accepted saving a stale run after editing it.
                new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                        lastRun.dynamicObjects(), snapshotFile(),
                        lastRun.modelSnapshot(),
                        lastRun.loadedDeclarations(), graphDiscoveryLedger);
                n = lastRun.dynamicObjects().size();
                report.append("Instances: ").append(n)
                      .append(" -> ").append(snapshotFile().getPath()).append('\n');
                // Register the dataset: model + rule-tree + snapshot saved
                // TOGETHER as one consistent triple, so the web serves it and a
                // snapshot is never paired with a mismatched model.
                registerDataset(runSig);
                report.append("Registry:  ")
                      .append(quiz.DatasetRegistry.defaultFile().getPath()).append('\n');

                File counts = countsFile();
                appendCountsRecord(counts, lastRun.dynamicObjects());
                report.append("Counts:    ").append(counts.getPath()).append('\n');
            } else {
                report.append("Instances: (none generated yet — run "
                                      + "\"Generate class instances\" first; not "
                                      + "registered until model + snapshot are "
                                      + "saved together)\n");
            }

            sourceWorkbench.refreshDomainOverview();
            // What is on disk is now what is open, so switching away asks nothing.
            markSaved(modelFile());
            logWindow.info("Saved domain \"" + projectModel.name() + "\":\n" + report);

            String hint = instanceCountHint(n);
            JOptionPane.showMessageDialog(
                    this,
                    report + (hint.isBlank() ? "" : "\n" + hint),
                    "Saved domain",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            reportGenerationError(ex);
        }
    }

    private void saveModelOnly() {
        try {
            sourceWorkbench.applyEdits();
            String plan = "Save the model \"" + projectModel.name() + "\"?\n\n"
                    + "Configuration: " + modelFile().getPath()
                    + "\n\nModels do not generate or save instances.";
            if (JOptionPane.showConfirmDialog(this, plan, "Save model",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE)
                    != JOptionPane.OK_OPTION) return;
            modelFile().getParentFile().mkdirs();
            new GeneratedProjectModelStore().save(projectModel, modelFile());
            markSaved(modelFile());
            rememberCurrentDomain();
            sourceWorkbench.refreshDomainOverview();
            refreshDomainBox();
            logWindow.info("Saved model \"" + projectModel.name() + "\" to "
                    + modelFile().getPath());
            JOptionPane.showMessageDialog(this, "Configuration: " + modelFile().getPath(),
                    "Saved model", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception failure) {
            reportGenerationError(failure);
        }
    }

    // The domain's counts log, alongside its snapshot: <domain>.counts.tsv.
    private File countsFile() {
        File snap = snapshotFile();
        return new File(snap.getParentFile(),
                        snap.getName().replaceFirst("\\.snapshot\\.json$", "") + ".counts.tsv");
    }

    // Appends ONE timestamped per-class row per save, so runs can be diffed over time —
    // the stable "perfect" counts vs a drift. What it counts lives in DomainCounts; the
    // earlier rows in an existing file answered a different question, so the format note
    // is written once where the meaning changes rather than silently reinterpreting them.
    private void appendCountsRecord(File file, List<WikidataDynamicObject> roots) {
        String row = java.time.LocalDateTime.now().withNano(0)
                + "\t" + DomainCounts.row(roots) + "\n";
        try {
            boolean fresh = !file.isFile();
            boolean noted = !fresh && java.nio.file.Files
                    .readString(file.toPath()).contains(DomainCounts.FORMAT_NOTE);
            try (java.io.FileWriter w = new java.io.FileWriter(file, true)) {
                if (fresh) {
                    w.write("# " + projectModel.name()
                                    + " — per-class counts, one row per save\n");
                }
                if (!noted) {
                    w.write(DomainCounts.FORMAT_NOTE + "\n");
                }
                w.write(row);
            }
        } catch (Exception ex) {
            logWindow.info("Could not write counts file: " + ex.getMessage());
        }
    }

    // Upserts this project's dataset (model + rule-tree + snapshot triple) into
    // the registry the web reads, so multiple domains coexist and are served.
    private void registerDataset(String snapshotModelSignature) {
        try {
            quiz.DatasetRegistry reg = quiz.DatasetRegistry.load();
            quiz.DatasetRegistry.Dataset d = new quiz.DatasetRegistry.Dataset();
            d.name(projectModel.name());
            d.key(projectKey());
            d.rootClass(projectModel.rootClass() == null
                                ? "" : projectModel.rootClass().className());
            d.modelPath(modelFile().getPath());
            d.ruletreePath(ruleTreeFile().getPath());
            d.snapshotPath(snapshotFile().getPath());
            d.modelSignature(snapshotModelSignature == null ? "" : snapshotModelSignature);
            d.savedAt(java.time.LocalDateTime.now().toString());
            java.util.List<String> types = new java.util.ArrayList<>();
            for (GeneratedClassModel c : projectModel.classes()) {
                if (c != null && c.className() != null) types.add(c.className());
            }
            d.types(types);
            reg.upsert(d);
            reg.save();
        } catch (Exception ex) {
            logWindow.info("Could not update dataset registry: " + ex.getMessage());
        }
    }

    // The Constellation membership (P31 = Q8928) yields ~92 hits, four more
    // than the 88 modern IAU constellations: the extras lack an IAU
    // abbreviation (P1813). Point the user at the filter that drops them.
    private String instanceCountHint(int n) {
        if (n == 92) {
            return "Hint: 92 includes 4 non-standard constellations. Tick "
                    + "\"Required\" on the IAU abbreviation (P1813) field and "
                    + "regenerate to get the canonical 88.";
        }
        return "";
    }

    private void showGeneratedSource() {
        // Shows the source for the selected class. Prefer the last compiled
        // run's source when it IS that class AND the model hasn't changed since
        // (so you see what actually compiled, incl. errors); otherwise show a
        // fresh preview from the CURRENT model — so applied edits (e.g. a new
        // field) show up without needing to regenerate first.
        sourceWorkbench.applyEdits();

        GeneratedClassModel cls = activeClass();
        String source;
        String title;

        boolean runMatchesClass = lastRun != null && lastRun.runtime() != null
                && lastRun.modelSnapshot() != null
                && lastRun.modelSnapshot().rootClass().className().equals(cls.className());
        boolean modelUnchanged = runMatchesClass
                && modelSignature(projectModel)
                .equals(modelSignature(lastRun.modelSnapshot()));

        if (modelUnchanged) {
            source = lastRun.runtime().source();
            title = lastRun.runtime().qualifiedClassName();
        } else {
            GeneratedViewableSourceGenerator gen =
                    new GeneratedViewableSourceGenerator(
                            GeneratedViewableSourceGenerator.GENERATED_PACKAGE);
            source = gen.sourceFor(cls, projectModel);
            title = gen.qualifiedClassName(cls)
                    + (runMatchesClass
                    ? "  (preview — model changed since last generate)"
                    : "  (preview - not yet compiled)");
        }

        JTextArea area = new JTextArea(source, 40, 120);

        area.setFont(
                new Font(
                        Font.MONOSPACED,
                        Font.PLAIN,
                        12));

        area.setCaretPosition(0);

        JOptionPane.showMessageDialog(
                this,
                new JScrollPane(area),
                title,
                JOptionPane.PLAIN_MESSAGE);
    }

    // Shows the compiled rule tree (the plan the generator walks to extract
    // and map instances), serialized from the current model — so you can see
    // the node/edge/included-field structure without running a generation.
    private void showRuleTree() {
        sourceWorkbench.applyEdits();

        String readable;
        String json;
        try {
            RuleNode root = RuleTreeCompiler.compileClass(activeClass(), projectModel);
            readable = RuleTreeExplainer.explain(root);
            json = new RuleTreeSerializer().mapper()
                                           .writeValueAsString(RuleTreeConfig.of(root));
        } catch (Exception ex) {
            reportGenerationError(ex);
            return;
        }

        JTextArea area = new JTextArea(readable, 40, 100);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        area.setCaretPosition(0);

        // Default to the plain-language plan; let the curious flip to the raw
        // RuleTreeConfig JSON (handy when reporting a compile bug).
        JToggleButton rawToggle = new JToggleButton("Show raw JSON");
        rawToggle.addActionListener(e -> {
            boolean raw = rawToggle.isSelected();
            area.setText(raw ? json : readable);
            area.setCaretPosition(0);
            rawToggle.setText(raw ? "Show readable plan" : "Show raw JSON");
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.add(rawToggle);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);

        JOptionPane.showMessageDialog(
                this,
                panel,
                "Rule tree — " + activeClass().className(),
                JOptionPane.PLAIN_MESSAGE);
    }

    private static JComponent titled(
            String title,
            JComponent component) {

        JPanel p =
                new JPanel(new BorderLayout());

        p.setBorder(
                BorderFactory.createTitledBorder(title));

        p.add(component, BorderLayout.CENTER);

        return p;
    }
}
