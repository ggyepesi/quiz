package domain.workbench.execution.ui;

import domain.build.GenerationRun;
import domain.build.GraphDiscoveryResultStore;
import domain.build.GraphResults;
import domain.build.WikidataGraphExpansionPlan;
import domain.config.ClassKind;
import domain.config.FieldProductionKind;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.PopulationSelection;
import domain.config.Selection;
import domain.datasource.graph.GraphEdgeDefinition;
import domain.datasource.schema.FieldType;
import domain.provenance.SourceIdentities;
import domain.runtime.WikidataDynamicObject;
import domain.workbench.execution.GenerationRunState;
import objectview.Viewable;
import objectview.render.RenderContext;
import objectview.utils.swing.SwingWindowActivation;
import process.swing.Dialogs;
import wikidata.WikidataIds;
import wikidata.ui.WikidataLinks;

import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Show instances for an open project's accepted run. The window owns the toolbar,
 * ordinary class and graph-annotation views, and their presentation state. The host
 * still owns applying graph results, starting workflows and changing the working model.
 * This viewer never loads or saves a project.
 */
public final class InstancesWindow implements AutoCloseable {
    private final Component dialogParent;
    private final GeneratedProjectModel model;
    private final GenerationRunState runState;
    private final GraphResults graphResults;
    private final Runnable openGraphFrontier;
    private final Consumer<GraphDiscoveryResultStore.Artifact> applyGraphResult;
    private final Supplier<File> modelFile;
    private final Consumer<PopulationSelection> populationCreated;

    private final QueryObjectResultPanel instances = new QueryObjectResultPanel();
    private final JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JButton nameCollisionsButton = new JButton("Name collisions");
    private final JButton graphFrontierButton = new JButton("Graph frontier");
    private final JButton createPopulationSelectionButton = new JButton("Create population selection…");
    private JFrame window;
    private JFrame nameCollisionsWindow;
    private NameCollisionInspectionPanel nameCollisionInspection;

    public InstancesWindow(Component dialogParent, GeneratedProjectModel model,
            GenerationRunState runState, GraphResults graphResults, Runnable openGraphFrontier,
            Consumer<GraphDiscoveryResultStore.Artifact> applyGraphResult,
            Supplier<File> modelFile, Consumer<PopulationSelection> populationCreated) {
        this.dialogParent = dialogParent;
        this.model = model;
        this.runState = runState;
        this.graphResults = graphResults;
        this.openGraphFrontier = openGraphFrontier;
        this.applyGraphResult = applyGraphResult;
        this.modelFile = modelFile;
        this.populationCreated = populationCreated;

        nameCollisionsButton.setToolTipText(
                "Names shared by several distinct entities (ambiguous quiz "
                        + "answers) — select a name to inspect the actual instances");
        nameCollisionsButton.addActionListener(e -> showNameCollisions());
        toolbar.add(nameCollisionsButton);
        graphFrontierButton.setToolTipText(
                "Review nodes reached by configured graph discovery but not yet "
                        + "expanded in the reverse direction");
        graphFrontierButton.addActionListener(e -> openGraphFrontier.run());
        toolbar.add(graphFrontierButton);
        createPopulationSelectionButton.addActionListener(
                ignored -> createPopulationSelection());
        toolbar.add(createPopulationSelectionButton);
        instances.onDisplayedObjectsChanged(
                this::updateCreatePopulationSelectionButton);
        updateCreatePopulationSelectionButton();

        nameCollisionsChanged();
    }

    public void show() {
        refresh();
        if (window == null) window = create(instances, toolbar);
        refreshTitle();
        SwingWindowActivation.showAndFocus(window);
    }

    /** Reproject the current run when the configuration or graph results change. */
    public void refresh() {
        GenerationRun run = runState.current();
        if (run == null) return;
        // The window has one projection: the accepted run plus every graph result still
        // owned by its named graph class. Sending the run on its own would remove valid
        // annotation tabs after Generate, Load or Enrich.
        ProjectInstancesPresentation.show(instances, model, run.objectResult(), graphResults.all(),
                result -> GraphResultPresentation.review(result, List.of(
                        new QueryObjectResultPanel.GroupAction("Apply accepted instances",
                                () -> applyGraphResult.accept(result))), runState::markUnsaved));
    }

    /** An accepted run can arrive after Show instances has already opened the frame. */
    public void refreshRun() {
        GenerationRun run = runState.current();
        if (run == null) {
            instances.clear();
        } else {
            // Graph state is presentation metadata, like the QID chip: card titles show it
            // without stored fields or any effect on search/sort. The fresh run's coverage
            // uses its snapshot, not the next edited plan.
            instances.cardDecorator(new GraphCoverageCardDecorator(runState.visibleGraphLedger(),
                    WikidataGraphExpansionPlan.compile(run.modelSnapshot()).edges()));
            refresh();
        }
        updateGraphFrontierButton();
        refreshTitle();
    }

    private void refreshTitle() {
        if (window == null) return;
        String title = instancesTitle(runState.current(), model);
        String annotations = graphResults.all().stream()
                .map(result -> result.type() + " " + result.instances().size())
                .collect(Collectors.joining(", "));
        window.setTitle(annotations.isBlank() ? title : title + " · graph annotations: " + annotations);
    }

    public void onHiddenClassSectionsChanged(Consumer<Set<String>> listener) {
        instances.onHiddenClassSectionsChanged(listener);
    }

    public Set<String> hiddenClassSections() {
        return instances.hiddenClassSections();
    }

    /** Called after the host replaces the project and restores its view preferences. */
    public void reset(Collection<String> hiddenClasses) {
        nameCollisionsChanged();
        instances.clear();
        instances.hiddenClassSections(hiddenClasses);
    }

    @Override public void close() {
        if (window != null) window.dispose();
        discardNameCollisionInspection();
    }

    JFrame window() { return window; }

    /**
     * Every edge with a reviewable frontier. A statement pattern is persisted in the
     * ledger; a field edge is derived from the model, so both are asked for here and
     * the two callers cannot disagree about which edges exist.
     */
    public List<GraphEdgeDefinition> reviewableEdges() {
        if (runState.current() == null) return List.of();
        return WikidataGraphExpansionPlan.compile(model).edges();
    }

    private void updateGraphFrontierButton() {
        var state = runState.visibleGraphLedger();
        int count = reviewableEdges().stream().mapToInt(
                edge -> state.frontier(edge).size()).sum();
        graphFrontierButton.setText(count == 0
                ? "Graph frontier" : "Graph frontier (" + count + ")");
        graphFrontierButton.setEnabled(runState.current() != null && count > 0);
    }

    /** A frontier decision changes the coverage decorations and the next frontier count. */
    public void frontierChanged() {
        instances.cardDecorator(new GraphCoverageCardDecorator(
                runState.graphLedger(), reviewableEdges()));
        updateGraphFrontierButton();
    }

    private void updateCreatePopulationSelectionButton() {
        Map<String, List<Viewable>> choices =
                populationSourcesShownIn(instances.displayedObjectsByType(), model);
        createPopulationSelectionButton.setEnabled(!choices.isEmpty());
        createPopulationSelectionButton.setToolTipText(choices.isEmpty()
                ? "No class with instances is currently shown"
                : "Create a reusable population from "
                        + (choices.size() == 1
                                ? choices.keySet().iterator().next()
                                : "one of the classes shown in this instances window"));
    }

    private void createPopulationSelection() {
        Map<String, List<Viewable>> choices =
                populationSourcesShownIn(instances.displayedObjectsByType(), model);
        if (choices.isEmpty()) return;
        String className;
        if (choices.size() == 1) {
            className = choices.keySet().iterator().next();
        } else {
            Object chosen = JOptionPane.showInputDialog(dialogParent,
                    "Create the population selection from which shown class?",
                    "Choose shown class", JOptionPane.QUESTION_MESSAGE, null,
                    choices.keySet().toArray(String[]::new),
                    choices.keySet().iterator().next());
            if (chosen == null) return;
            className = chosen.toString();
        }
        List<String> qids = choices.get(className).stream()
                .map(SourceIdentities::wikidataQid)
                .filter(Objects::nonNull).distinct().toList();
        if (qids.isEmpty()) {
            JOptionPane.showMessageDialog(dialogParent,
                    "No shown " + className + " instances have Wikidata QIDs.");
            return;
        }
        String entered = JOptionPane.showInputDialog(dialogParent, "Population selection name:",
                className + "Population");
        if (entered == null) return;
        String name = entered.trim();
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            JOptionPane.showMessageDialog(dialogParent,
                    "Enter a population selection name shaped like a Java class name.");
            return;
        }
        Selection existing = model.findSelection(name);
        if (existing != null && existing.isImported()) {
            JOptionPane.showMessageDialog(dialogParent, "Population " + name
                    + " is imported from " + existing.importedFrom()
                    + " and must be changed there.");
            return;
        }
        String description = "Create population selection \"" + name + "\" from all "
                + qids.size() + " shown " + className + " instances.\n\n"
                + "Save " + (model.isModel() ? "model" : "domain")
                + " will write it to\n" + modelFile.get().getPath() + ".";
        if (!Dialogs.confirmPersistence(
                dialogParent, "Create population selection", description)) return;
        PopulationSelection population = new PopulationSelection(name);
        population.className(className);
        population.instanceQids(qids);
        populationCreated.accept(population);
    }

    static Map<String, List<Viewable>> populationSourcesShownIn(
            Map<String, List<Viewable>> shown,
            GeneratedProjectModel project) {
        Map<String, List<Viewable>> choices =
                new LinkedHashMap<>();
        if (shown == null || project == null) return choices;
        shown.forEach((name, values) -> {
            GeneratedClassModel clazz = project.findClass(name);
            if (clazz != null
                    && clazz.classKind() != ClassKind.GRAPH
                    && values != null && !values.isEmpty()) {
                choices.put(name, values);
            }
        });
        return Collections.unmodifiableMap(choices);
    }

    static String instancesTitle(GenerationRun run, GeneratedProjectModel model) {
        if (run == null) {
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
        Map<String, Set<String>> qidsByType =
                new LinkedHashMap<>();
        if (run.dynamicObjects() != null) {
            for (WikidataDynamicObject o : run.dynamicObjects()) {
                // Unstamped objects are demoted duplicate statements or bare reference
                // children, not class instances. typeName() would fall back to the Java
                // class name, so ask about the stamp itself.
                if (o == null || !o.hasTypeStamp()) {
                    continue;
                }
                String qid = o.qid();
                if (qid == null || !WikidataIds.isQid(qid)) {
                    continue;   // not a saved entity — don't inflate the count
                }
                qidsByType.computeIfAbsent(
                        o.typeName(), k -> new HashSet<>()).add(qid);
            }
        }
        if (qidsByType.isEmpty()) {
            return "Generated instances  (" + run.size() + ")";
        }
        int total = qidsByType.values().stream().mapToInt(Set::size).sum();
        String breakdown = qidsByType.entrySet().stream()
                                     .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                                     .map(e -> e.getKey() + " " + e.getValue().size())
                                     .collect(Collectors.joining(", "));
        return "Generated instances — " + breakdown
                + "  (" + total + " distinct)" + partsNote(model, run);
    }

    /** A single-class preview deliberately does NOT materialize owned components: one
     *  empty part per instance answers neither question a preview asks. Say so on the
     *  panel, or their absence reads as a fault in the model. */
    private static String partsNote(GeneratedProjectModel model, GenerationRun run) {
        boolean modelHasParts = model != null && model.classes().stream()
                .filter(Objects::nonNull)
                .anyMatch(clazz -> clazz.fields().stream().anyMatch(field ->
                        field != null && field.type()
                                == FieldType.ENTITY
                                && field.mapping().productionKind()
                                == FieldProductionKind.OWNED_COMPONENT));
        if (!modelHasParts || run == null || run.dynamicObjects() == null) {
            return "";
        }
        boolean anyPart = run.dynamicObjects().stream()
                .anyMatch(o -> o != null && o.isPart());
        return anyPart ? "" : "   ⚠ parts not materialized (preview — "
                + "Generate domain or Enrich produces them with their values)";
    }

    public void nameCollisionsChanged() {
        // The collision browser belongs to a concrete run, not to a label or count.
        discardNameCollisionInspection();
        int n = runState.nameCollisions().size();
        nameCollisionsButton.setText(
                n == 0 ? "Name collisions" : "Name collisions (" + n + ")");
        nameCollisionsButton.setEnabled(n > 0);
    }

    private void showNameCollisions() {
        if (runState.nameCollisions().isEmpty()) {
            return;
        }
        if (nameCollisionsWindow != null && nameCollisionsWindow.isDisplayable()) {
            SwingWindowActivation.showAndFocus(nameCollisionsWindow);
            return;
        }
        RenderContext context = new RenderContext();
        context.setInPlaceNavigation(true);
        context.setValueLinker(WikidataLinks.valueLinker());
        if (nameCollisionInspection != null) nameCollisionInspection.close();
        nameCollisionInspection = new NameCollisionInspectionPanel(runState.nameCollisions(), context);

        if (nameCollisionsWindow == null || !nameCollisionsWindow.isDisplayable()) {
            nameCollisionsWindow = new JFrame("Name collisions");
            nameCollisionsWindow.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            nameCollisionsWindow.setSize(1200, 760);
            nameCollisionsWindow.setLocationByPlatform(true);
        }
        nameCollisionsWindow.getContentPane().removeAll();
        nameCollisionsWindow.add(nameCollisionInspection, BorderLayout.CENTER);
        nameCollisionsWindow.revalidate();
        nameCollisionsWindow.repaint();
        SwingWindowActivation.showAndFocus(nameCollisionsWindow);
    }

    private void discardNameCollisionInspection() {
        if (nameCollisionInspection != null) {
            nameCollisionInspection.close();
            nameCollisionInspection = null;
        }
        if (nameCollisionsWindow != null) {
            nameCollisionsWindow.dispose();
            nameCollisionsWindow = null;
        }
    }

    /** Window shell for the saved-domain viewer, which has no GenerationRun yet. */
    public static JFrame create(QueryObjectResultPanel instances, JComponent toolbar) {
        var window = new JFrame("Generated instances");
        window.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        if (toolbar != null) window.add(toolbar, BorderLayout.NORTH);
        window.add(instances, BorderLayout.CENTER);
        window.setSize(1100, 850);
        window.setLocationByPlatform(true);
        return window;
    }
}
