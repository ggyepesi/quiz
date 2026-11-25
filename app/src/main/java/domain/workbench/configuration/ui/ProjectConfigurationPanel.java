package domain.workbench.configuration.ui;

import domain.config.GeneratedProjectModel;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.PopulationSelection;
import domain.provenance.SourceIdentities;
import domain.transform.ui.ProjectBacking;
import domain.transform.ui.TransformController;
import domain.transform.ui.TransformWorkbenchPanel;
import domain.transform.ui.WorkbenchPurpose;
import domain.transform.pipeline.ui.TransformationConfigurationEditor;
import domain.workbench.modelbuilder.ui.ModelSourceWorkbenchPanel;
import domain.workbench.modelbuilder.ui.SelectionViewerPanel;
import domain.workbench.modelbuilder.ui.SingleRootClassModelPanel;
import objectview.Viewable;
import process.swing.Dialogs;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** The ModelBuilder configuration navigator and detail editors over an open project. */
public final class ProjectConfigurationPanel extends JPanel implements AutoCloseable {
    private final ModelSourceWorkbenchPanel details;
    private final SingleRootClassModelPanel constructs;

    public ProjectConfigurationPanel(ProjectBacking project, TransformController controller,
            WorkbenchPurpose purpose, List<? extends Viewable> selectedInstances, Object initialConstruct,
            Runnable populationChanged) {
        this(project, controller.domain().instances().size(), initialConstruct, (details, selections) -> {
            GeneratedProjectModel model = project.projectModel();
            selections.inspect(selection -> purpose.showsTransformActions() && !selection.isImported()
                            && selection instanceof PopulationSelection population
                            && !isTransformationOutput(model, population),
                    population -> selectedInstances.stream()
                            .filter(value -> controller.domain().isInstanceOf(value, population.className()))
                            .map(SourceIdentities::wikidataQid)
                            .filter(wikidata.WikidataIds::isQid).distinct().toList());
            selections.afterChange(selection -> {
                if (selection instanceof PopulationSelection population) project.populationChanged(population);
                populationChanged.run();
            });
            var fields = purpose.showsTransformActions() ? transformationFields(controller) : null;
            details.inspectConfiguration(fields, clazz -> transformationApplied(project, clazz));
        }, ignored -> { });
    }

    /** Mounts field and transformation editing over the unified workbench's open project. */
    public static ProjectConfigurationPanel editWorkingConfiguration(ProjectBacking project,
            TransformController controller, Consumer<Object> selected,
            Consumer<GeneratedFieldModel> fieldApplied,
            Runnable recipeApplied) {
        return new ProjectConfigurationPanel(project, controller.domain().instances().size(),
                project.projectModel().rootClass(),
                (details, selections) -> {
            selections.inspect(ignored -> false, ignored -> List.of());
            details.editWorkingConfiguration(transformationFields(controller), clazz -> {
                transformationApplied(project, clazz);
                recipeApplied.run();
            });
            details.afterChange(ignored -> {
                if (details.selected() instanceof GeneratedFieldModel field) fieldApplied.accept(field);
            });
        }, selected);
    }

    private static TransformationConfigurationEditor.Fields transformationFields(TransformController controller) {
        return new TransformationConfigurationEditor.Fields(controller.domain(),
                controller::candidateValueSelection, controller::sampleFieldValue);
    }

    private static void transformationApplied(ProjectBacking project, GeneratedClassModel clazz) {
        project.recordModelChange("Population transformation for " + clazz.className());
    }

    private boolean restoringSelection;
    private boolean editingAllowed = true;
    private java.util.function.BiFunction<String, String, process.swing.Dialogs.UnappliedEdits>
            unappliedDecision = (construct, action) ->
                    process.swing.Dialogs.unappliedEdits(this, construct, action);

    private ProjectConfigurationPanel(ProjectBacking project, int loadedObjects, Object initialConstruct,
            BiConsumer<ModelSourceWorkbenchPanel, SelectionViewerPanel> configure,
            Consumer<Object> selected) {
        super(new BorderLayout());
        GeneratedProjectModel model = project.projectModel();
        constructs = new SingleRootClassModelPanel(model, false);
        details = new ModelSourceWorkbenchPanel(model);
        var selections = new SelectionViewerPanel(model, null, null);
        details.selectionEditor(selections);
        configure.accept(details, selections);
        details.loadedProjectStatus(project.modelFile().isFile(), project.snapshotFile().isFile(), loadedObjects);
        details.onConfigurationNavigation(constructs::selectConstruct);
        details.onGraphSelection(constructs::selectConstruct);
        constructs.addTreeSelectionListener(event -> {
            if (restoringSelection) return;
            Object construct = constructs.selectedUserObject();
            if (SingleRootClassModelPanel.isConfigurable(construct) && construct != details.selected()) {
                // Leaving a construct with unapplied edits asks first (#535); Stay keeps
                // both the edits and the selection.
                if (!editingAllowed || !resolveUnappliedEdits("opening another construct")) {
                    restoringSelection = true;
                    try {
                        constructs.selectConstruct(details.selected());
                    } finally {
                        restoringSelection = false;
                    }
                    return;
                }
                details.edit(construct);
                selected.accept(construct);
            }
        });
        constructs.selectConstruct(initialConstruct);
        if (details.selected() == null) {
            details.edit(constructs.selectedUserObject());
            selected.accept(details.selected());
        }
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, constructs, details);
        split.setDividerLocation(230);
        add(split, BorderLayout.CENTER);
    }

    private static boolean isTransformationOutput(GeneratedProjectModel model,
            PopulationSelection population) {
        var output = model.findClass(population.className());
        return output != null && output.materializedByTransformation();
    }

    public boolean hasDrafts() { return details.hasTransformationDrafts(); }

    public void setEditingEnabled(boolean enabled) {
        editingAllowed = enabled;
        details.setEditingEnabled(enabled);
    }

    /**
     * Before an action that needs the open editor's draft settled: asks Apply /
     * Discard / Stay. Returns whether the action may continue; Discard reloads the
     * editor from the model, and a refused Apply stays.
     */
    public boolean resolveUnappliedEdits(String action) {
        if (!details.hasUnappliedEdits()) return true;
        return switch (unappliedDecision.apply(details.editedConstructName(), action)) {
            case APPLY -> details.applyUnappliedEdits();
            case DISCARD -> {
                discardUnappliedEdits();
                yield true;
            }
            case STAY -> false;
        };
    }

    public boolean hasUnappliedEdits() { return details.hasUnappliedEdits(); }

    /** Applies the open editor's edits as its Apply does; false when Apply refused. */
    public boolean applyUnappliedEdits() { return details.applyUnappliedEdits(); }

    public String editedConstructName() { return details.editedConstructName(); }

    /** Reloads the open construct from the working model, dropping its draft. */
    public void discardUnappliedEdits() { details.discardUnappliedEdits(); }

    /** Lets a test or host answer the question instead of the shared dialog. */
    public void unappliedDecision(java.util.function.BiFunction<String, String,
            process.swing.Dialogs.UnappliedEdits> decision) {
        unappliedDecision = decision;
        details.unappliedDecision(decision);
    }

    /** Application composition: mount the shared model editor over this workbench's scope. */
    public static void open(TransformWorkbenchPanel.ConfigurationContext context) {
        var panel = new ProjectConfigurationPanel(context.project(), context.controller(),
                context.purpose(), context.selectedInstances(), context.selectedConstruct(),
                context.populationChanged());
        JDialog dialog = Dialogs.reviewDialog(context.owner(),
                "Configuration — " + context.project().projectName());
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (panel.hasDrafts()) {
                    Object[] options = {"Discard changes", "Keep editing"};
                    int answer = JOptionPane.showOptionDialog(dialog,
                            "Discard the unapplied transformation edits?",
                            "Unapplied transformation edits", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE, null, options, options[1]);
                    if (answer != 0) return;
                }
                dialog.dispose();
            }

            @Override public void windowClosed(WindowEvent event) { panel.close(); }
        });
        dialog.add(panel);
        dialog.setMinimumSize(new Dimension(780, 460));
        dialog.setSize(1120, 690);
        dialog.setLocationRelativeTo(context.owner());
        dialog.setVisible(true);
    }

    @Override public void close() { details.close(); }
}
