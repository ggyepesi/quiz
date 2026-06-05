package wikidata.explore.tree;

import aux.SplitPaneUtils;
import wikidata.WikidataSparqlClient;
import wikidata.explore.wikiproject.WikiProjectArticle;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.List;

public class WikidataRuleTreeFrame extends JFrame {

    private static final String BASE_TITLE = "Wikidata Recursive Rule Explorer";

    private final WikidataSparqlClient client;
    private final RuleTreeExtractor extractor;
    private final RuleTreeSerializer serializer = new RuleTreeSerializer();

    private final JTextArea debugArea = new JTextArea(9, 90);

    private final JSpinner childDepthSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 5, 1));

    private final JButton loadButton = new JButton("Load node instances");

    private final JButton cancelButton = new JButton("Cancel");
    private final RuleNodeEditorPanel editorPanel = new RuleNodeEditorPanel();
    private final CachedPropertyQuizablePanel propertyPanel = new CachedPropertyQuizablePanel();
    private final WikidataObjectTreePanel resultPanel = new WikidataObjectTreePanel();
    private final NodeSamplePanel samplePanel = new NodeSamplePanel();
    private final NodePropertyDiscoveryPanel discoveryPanel = new NodePropertyDiscoveryPanel();
    private final WikiProjectSeedPanel wikiProjectPanel = new WikiProjectSeedPanel();
    private final JTabbedPane nodeWorkbenchTabs = new JTabbedPane();
    private SwingWorker<List<WikidataDynamicObject>, String> currentLoadWorker;
    private RuleNode rootNode = createDefaultRootNode();
    private final RuleTreePanel ruleTreePanel = new RuleTreePanel(rootNode);
    private File lastFile;
    private RuleTreeConfig currentConfig = RuleTreeConfig.of(rootNode);

    public WikidataRuleTreeFrame(WikidataSparqlClient client) {
        super(BASE_TITLE);

        this.client = client;

        this.extractor = new RuleTreeExtractor(client);

        this.extractor.log(this::appendDebug);

        currentConfig.name(rootNode.name());
        currentConfig.description("Default demo configuration");

        buildUi();
        wireActions();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1700, 950);
        setLocationByPlatform(true);

        updateTitle();
    }

    private static RuleNode createDefaultRootNode() {
        RuleNode root = new RuleNode("Constellation", "constellation");

        root.sourceQid("Q8928");
        root.sourceLabel("constellations");
        root.propertyPid("P31");
        root.propertyLabel("instance of");
        root.direction(RuleDirection.ITEM_TO_ROOT);
        root.limit(200);
        root.labelConfig(new RuleLabelConfig(true, "en"));

        return root;
    }

    private static File ensureJsonExtension(File file) {
        if (file == null) {
            return null;
        }

        return file.getName().toLowerCase().endsWith(".json") ? file : new File(file.getPath() + ".json");
    }

    private static String safeConfigName(RuleTreeConfig config) {
        if (config == null || config.name() == null || config.name().isBlank()) {
            return "(unnamed)";
        }

        return config.name();
    }

    private static JComponent titled(String title, JComponent component) {

        JPanel panel = new JPanel(new BorderLayout());

        panel.setBorder(BorderFactory.createTitledBorder(title));

        panel.add(component, BorderLayout.CENTER);

        return panel;
    }

    private void buildUi() {
        setLayout(new BorderLayout(6, 6));
        add(buildToolbar(), BorderLayout.NORTH);

        nodeWorkbenchTabs.addTab("Configure", editorPanel);
        nodeWorkbenchTabs.addTab("Sample", samplePanel);
        nodeWorkbenchTabs.addTab("Discover", discoveryPanel);
        nodeWorkbenchTabs.addTab("WikiProject", wikiProjectPanel);
        nodeWorkbenchTabs.addTab("Properties", propertyPanel);

        JSplitPane leftCenterSplit = SplitPaneUtils.horizontal(titled("Rule tree", ruleTreePanel), titled("Selected node workbench", nodeWorkbenchTabs), 0.28);

        JSplitPane mainSplit = SplitPaneUtils.horizontal(leftCenterSplit, titled("Results", resultPanel), 0.72);

        debugArea.setEditable(false);
        debugArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane logScroll = new JScrollPane(debugArea);

        logScroll.setPreferredSize(new Dimension(1000, 180));
        logScroll.setMinimumSize(new Dimension(100, 120));

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainSplit, titled("Log / SPARQL", logScroll));

        vertical.setResizeWeight(0.75);
        vertical.setDividerSize(8);
        vertical.setOneTouchExpandable(true);
        vertical.setContinuousLayout(true);

        SwingUtilities.invokeLater(() -> vertical.setDividerLocation(0.75));
        add(vertical, BorderLayout.CENTER);
    }

    private JComponent buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));

        JButton previewButton = new JButton("Preview SPARQL");

        JButton clearLogButton = new JButton("Clear log");

        JButton saveConfig = new JButton("Save");

        JButton saveAsConfig = new JButton("Save As...");

        JButton loadConfig = new JButton("Load config...");

        cancelButton.setEnabled(false);

        toolbar.add(loadButton);
        toolbar.add(cancelButton);
        toolbar.add(previewButton);
        toolbar.add(new JLabel("Recursive child depth:"));
        toolbar.add(childDepthSpinner);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(saveConfig);
        toolbar.add(saveAsConfig);
        toolbar.add(loadConfig);
        toolbar.add(clearLogButton);

        loadButton.addActionListener(e -> loadResults());
        cancelButton.addActionListener(e -> cancelLoad());
        previewButton.addActionListener(e -> previewQueries());
        clearLogButton.addActionListener(e -> debugArea.setText(""));

        saveConfig.addActionListener(e -> saveConfig(false));
        saveAsConfig.addActionListener(e -> saveConfig(true));
        loadConfig.addActionListener(e -> loadConfig());

        return toolbar;
    }

    private void wireActions() {
        editorPanel.afterChange(() -> {
            ruleTreePanel.refresh();
            discoveryPanel.refreshNodeTitle();
            samplePanel.repaint();
        });

        editorPanel.log(this::appendDebug);
        editorPanel.editNode(rootNode);

        propertyPanel.onPropertySelected(editorPanel::useProperty);

        ruleTreePanel.addTreeSelectionListener(e -> {
            Object selected = ruleTreePanel.selectedUserObject();

            if (selected instanceof RuleNode node) {
                editorPanel.editNode(node);
                discoveryPanel.refreshNodeTitle();
            } else if (selected instanceof RuleEdge edge) {
                editorPanel.editNode(edge.childNode());
                discoveryPanel.refreshNodeTitle();
            }
        });

        samplePanel.setClient(client);
        samplePanel.setApplyEdits(() -> {
            editorPanel.applyCurrentNodeEdits();
            ruleTreePanel.refresh();
            discoveryPanel.refreshNodeTitle();
        });
        samplePanel.setNodeSupplier(editorPanel::currentNode);

        discoveryPanel.setClient(client);
        discoveryPanel.setApplyEdits(() -> {
            editorPanel.applyCurrentNodeEdits();
            ruleTreePanel.refresh();
            discoveryPanel.refreshNodeTitle();
        });
        discoveryPanel.setNodeSupplier(editorPanel::currentNode);

        discoveryPanel.onAddChildEdge(p -> {
            editorPanel.addChildEdgeFromProperty(p.pid(), p.label(), p.fieldName(), true);

            ruleTreePanel.refresh();
            nodeWorkbenchTabs.setSelectedComponent(editorPanel);

            appendDebug("Added discovered property as child edge: " + p.label() + " (" + p.pid() + ")\n");
        });

        discoveryPanel.onAddIncludedField(p -> {
            editorPanel.addIncludedFieldFromProperty(p.pid(), p.label(), p.fieldName(), p.kind());

            ruleTreePanel.refresh();
            nodeWorkbenchTabs.setSelectedComponent(editorPanel);

            appendDebug("Added discovered property as included field: " + p.label() + " (" + p.pid() + ")\n");
        });

        discoveryPanel.onAddAllowedQid(qid -> {
            editorPanel.addIncludedQids(java.util.List.of(qid));
            ruleTreePanel.refresh();
            appendDebug("Added allowed QID from discovered example: " + qid + "\n");
        });

        discoveryPanel.onAddExcludedQid(qid -> {
            editorPanel.addExcludedQids(java.util.List.of(qid));
            ruleTreePanel.refresh();
            appendDebug("Added excluded QID from discovered example: " + qid + "\n");
        });

        discoveryPanel.refreshNodeTitle();

        wikiProjectPanel.setSparqlClient(client);

        wikiProjectPanel.onAddIncludedQids(articles -> {
            List<String> qids = articles.stream().map(WikiProjectArticle::qid).filter(qid -> qid != null && qid.matches("Q\\d+")).toList();

            editorPanel.addIncludedQids(qids);
            ruleTreePanel.refresh();

            appendDebug("Added " + qids.size() + " WikiProject QIDs to included QIDs.\n");
        });

        wikiProjectPanel.onReplaceIncludedQids(articles -> {
            List<String> qids = articles.stream().map(WikiProjectArticle::qid).filter(qid -> qid != null && qid.matches("Q\\d+")).toList();

            editorPanel.replaceIncludedQids(qids);
            ruleTreePanel.refresh();

            appendDebug("Replaced included QIDs with " + qids.size() + " WikiProject QIDs.\n");
        });

        wikiProjectPanel.onUseAsSourceQid(article -> {
            editorPanel.useSourceQid(article.qid(), article.title());
            ruleTreePanel.refresh();

            appendDebug("Set source QID from WikiProject seed: " + article.title() + " (" + article.qid() + ")\n");
        });
    }

    private void saveConfig(boolean forceChooseFile) {
        editorPanel.applyCurrentNodeEdits();
        ruleTreePanel.refresh();

        File file = forceChooseFile || lastFile == null ? chooseFile(false) : lastFile;

        if (file == null) {
            return;
        }

        file = ensureJsonExtension(file);

        try {
            RuleTreeConfig config = buildCurrentConfigForSave();

            serializer.save(config, file);

            currentConfig = config;

            lastFile = file;

            updateTitle();

            appendDebug("Config saved to: " + file.getAbsolutePath() + "\n");
        } catch (Exception ex) {
            appendDebug("ERROR saving config: " + ex.getMessage() + "\n");

            JOptionPane.showMessageDialog(this,
                                          "Could not save config:\n"
                                                  + ex.getMessage(),
                                          "Save failed",
                                          JOptionPane.ERROR_MESSAGE);
        }
    }

    private RuleTreeConfig buildCurrentConfigForSave() {
        RuleTreeConfig config = currentConfig == null ? RuleTreeConfig.of(rootNode) : currentConfig;

        config.root(rootNode);

        if (config.name() == null || config.name().isBlank()) {
            config.name(rootNode.name());
        }

        if (config.version() <= 0) {
            config.version(RuleTreeConfig.CURRENT_VERSION);
        }

        return config;
    }

    private void loadConfig() {
        File file = chooseFile(true);

        if (file == null) {
            return;
        }

        try {
            RuleTreeConfig loadedConfig = serializer.loadConfig(file);

            RuleNode loaded = loadedConfig.root();

            lastFile = file;

            currentConfig = loadedConfig;

            rootNode = loaded;

            ruleTreePanel.setRootNode(loaded);
            ruleTreePanel.selectRoot();
            editorPanel.editNode(loaded);
            discoveryPanel.refreshNodeTitle();

            updateTitle();

            appendDebug("Config loaded from: " + file.getAbsolutePath() + "\n");

            appendDebug("Config name: " + safeConfigName(loadedConfig) + "\n");

            appendDebug("Config version: " + loadedConfig.version() + "\n");

            appendDebug("Root node: " + loaded.displayName() + "\n");
        } catch (Exception ex) {
            appendDebug("ERROR loading config: " + ex.getMessage() + "\n");

            JOptionPane.showMessageDialog(this, "Could not load config:\n" + ex.getMessage(), "Load failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private File chooseFile(boolean open) {
        JFileChooser chooser = new JFileChooser();

        chooser.setFileFilter(new FileNameExtensionFilter("Rule tree config (*.json)", "json"));

        if (lastFile != null) {
            File parent = lastFile.getParentFile();

            if (parent != null) {
                chooser.setCurrentDirectory(parent);
            }

            chooser.setSelectedFile(lastFile);
        }

        int result = open ? chooser.showOpenDialog(this) : chooser.showSaveDialog(this);

        return result == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null;
    }

    private void previewQueries() {
        editorPanel.applyCurrentNodeEdits();
        ruleTreePanel.refresh();

        appendDebug("\nPreview SPARQL queries\n======================\n");

        List<String> queries = extractor.previewQueries(rootNode, childDepth());

        appendDebug("Configuration root: " + rootNode.displayName() + "\n");

        appendDebug("Result meaning: each result item is an instance of the configured RuleNode.\n\n");

        appendDebug("Number of query templates: " + queries.size() + "\n\n");

        for (int i = 0; i < queries.size(); i++) {
            appendDebug("Query/template " + (i + 1) + "\n--------------\n");

            appendDebug(queries.get(i));
            appendDebug("\n");
        }

        if (childDepth() > 0) {
            appendDebug("Note: child queries are templates. " + "At runtime they run once per parent result batch.\n");
        }
    }

    private void loadResults() {
        if (currentLoadWorker != null && !currentLoadWorker.isDone()) {
            appendDebug("A load is already running. Cancel it first.\n");
            return;
        }

        editorPanel.applyCurrentNodeEdits();
        ruleTreePanel.refresh();

        setLoadBusy(true);

        appendDebug("\nLoad requested\n==============\n");
        appendDebug("Configuration root: " + rootNode.displayName() + "\n");

        appendDebug("Result meaning: loaded rows are instances of node " + rootNode.name() + ".\n");

        appendDebug("Recursive child depth: " + childDepth() + "\n");

        int depth = childDepth();

        RuleNode rootSnapshot = rootNode;

        currentLoadWorker = new SwingWorker<>() {

            @Override
            protected List<WikidataDynamicObject> doInBackground() throws Exception {

                return extractor.load(rootSnapshot, depth, this::publish);
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    appendDebug(chunk);
                }
            }

            @Override
            protected void done() {
                setLoadBusy(false);

                if (isCancelled()) {
                    appendDebug("Load cancelled.\n");
                    currentLoadWorker = null;
                    return;
                }

                try {
                    List<WikidataDynamicObject> objects = get();

                    resultPanel.setObjects(objects);

                    appendDebug("Loaded node instances: " + objects.size() + "\n");
                } catch (Exception ex) {
                    appendDebug("ERROR: " + ex.getMessage() + "\n");

                    ex.printStackTrace();

                    JOptionPane.showMessageDialog(WikidataRuleTreeFrame.this, "Load failed:\n" + ex.getMessage(), "Load failed", JOptionPane.ERROR_MESSAGE);
                } finally {
                    currentLoadWorker = null;
                }
            }
        };

        currentLoadWorker.execute();
    }

    private void cancelLoad() {
        if (currentLoadWorker != null && !currentLoadWorker.isDone()) {
            appendDebug("Cancelling load...\n");
            currentLoadWorker.cancel(true);
            setLoadBusy(false);
        }
    }

    private void setLoadBusy(boolean busy) {
        loadButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private int childDepth() {
        return ((Number) childDepthSpinner.getValue()).intValue();
    }

    private void appendDebug(String s) {
        if (SwingUtilities.isEventDispatchThread()) {
            debugArea.append(s);
            debugArea.setCaretPosition(debugArea.getDocument().getLength());
        } else {
            SwingUtilities.invokeLater(() -> appendDebug(s));
        }
    }

    private void updateTitle() {
        StringBuilder title = new StringBuilder(BASE_TITLE);

        if (lastFile != null) {
            title.append(" - ").append(lastFile.getName());
        } else if (currentConfig != null && currentConfig.name() != null && !currentConfig.name().isBlank()) {
            title.append(" - ").append(currentConfig.name());
        }

        setTitle(title.toString());
    }
}
