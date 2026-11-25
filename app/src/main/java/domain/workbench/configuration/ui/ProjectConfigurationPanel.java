package domain.workbench.configuration.ui;

import domain.config.GeneratedProjectModel;
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

/** The ModelBuilder configuration navigator and detail editors over an open project. */
public final class ProjectConfigurationPanel extends JPanel implements AutoCloseable {
    private final ModelSourceWorkbenchPanel details;

    public ProjectConfigurationPanel(ProjectBacking project, TransformController controller,
            WorkbenchPurpose purpose, List<? extends Viewable> selectedInstances, Object initialConstruct,
            Runnable populationChanged) {
        super(new BorderLayout());
        GeneratedProjectModel model = project.projectModel();
        var constructs = new SingleRootClassModelPanel(model, false);
        details = new ModelSourceWorkbenchPanel(model);
        var selections = new SelectionViewerPanel(model, null, null);
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
        details.selectionEditor(selections);
        var fields = purpose.showsTransformActions() ? new TransformationConfigurationEditor.Fields(
                controller.domain(), controller::candidateValueSelection,
                controller::sampleFieldValue) : null;
        details.inspectConfiguration(fields, clazz -> project.recordModelChange(
                "Population transformation for " + clazz.className()));
        details.loadedProjectStatus(
                project.modelFile().isFile(), project.snapshotFile().isFile(),
                controller.domain().instances().size());
        details.onConfigurationNavigation(constructs::selectConstruct);
        details.onGraphSelection(constructs::selectConstruct);
        constructs.addTreeSelectionListener(event -> {
            Object selected = constructs.selectedUserObject();
            if (SingleRootClassModelPanel.isConfigurable(selected)) details.edit(selected);
        });
        constructs.selectConstruct(initialConstruct);
        if (details.selected() == null) details.edit(constructs.selectedUserObject());
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
