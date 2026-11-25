package domain.workbench.unified.ui;

import domain.DomainModel;
import domain.DomainSchemas;
import domain.compile.transform.TransformationExecution;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;
import domain.config.ModelSourceExecutionPlan;
import domain.config.OwnedFieldSources;
import domain.datasource.Datasources;
import domain.datasource.api.ConfigurationElement;
import domain.datasource.api.DatasourceRegistry;
import domain.datasource.api.SourceExecutionPlan;
import domain.transform.ui.ProjectBacking;
import domain.transform.ui.FieldDefinitions;
import domain.transform.ui.TransformController;
import domain.transform.ui.ValidationPanel;
import domain.transform.ui.WorkbenchPurpose;
import domain.workbench.configuration.ui.ProjectConfigurationPanel;
import domain.workbench.execution.ui.SwingQuerySession;
import domain.workbench.ui.WorkbenchActionResultNavigator;
import domain.workbench.ui.WorkbenchResultViews;
import objectview.view.SearchableView;
import process.swing.Dialogs;
import process.swing.SwingCancellationConfirmation;
import work.QueryContext;
import work.Query;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import javax.swing.JRootPane;
import java.awt.FlowLayout;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One opened project for field acquisition and local transformations. */
public final class UnifiedWorkbenchPanel extends JPanel implements AutoCloseable {
    private final DomainModel domain;
    private final ProjectBacking project;
    private final SwingQuerySession queries;
    private final ProjectConfigurationPanel configuration;
    private final TransformController transform;
    private final WorkbenchActionResultNavigator actions = new WorkbenchActionResultNavigator();
    private final WorkbenchActionResultNavigator.Action previewAction;
    private final WorkbenchResultViews previewResults = new WorkbenchResultViews();
    private final JButton preview = new JButton("Preview population");
    private final JLabel previewStatus = new JLabel();
    private final DatasourceRegistry datasources = Datasources.standard();
    private final JTextArea planText = new JTextArea(8, 34);
    private final JButton save;
    private final JLabel saveStatus = new JLabel();
    private final JPanel acquisitionDetail = new JPanel(new BorderLayout());
    private final JPanel acquisitionValues = new JPanel(new BorderLayout());
    private final String saveVerb;
    private SourceExecutionPlan plan;
    private SourceExecutionPlan selectedFieldPlan;
    private String planProblem;
    private boolean saving;
    private boolean loadingValues;
    private boolean previewing;
    private boolean previewCompleted;
    private boolean previewActionSelected;
    private boolean loadActionSelected;
    private boolean acquisitionTargetCurrent;
    private GeneratedFieldModel selectedField;
    private GeneratedClassModel selectedClass;
    private GeneratedClassModel previewInput;
    private ValidationPanel valuePanel;

    public UnifiedWorkbenchPanel(DomainModel domain) {
        this(domain, new SwingQuerySession(new QueryContext()));
    }

    public UnifiedWorkbenchPanel(DomainModel domain, SwingQuerySession queries) {
        super(new BorderLayout(4, 4));
        this.domain = domain;
        this.queries = queries;
        transform = new TransformController(domain, null);
        project = Objects.requireNonNull(domain.capability(ProjectBacking.class),
                "Open a model-backed project to edit field configuration");
        queries.logs().saveContext(project.projectName(),
                project.modelFile().getAbsoluteFile().getParentFile().toPath());
        saveVerb = "Save " + project.projectKind().name().toLowerCase(Locale.ROOT);
        save = new JButton(saveVerb + "…");
        save.addActionListener(event -> confirmSave());
        planText.setName("field.acquisition.plan");
        planText.setEditable(false);
        planText.setLineWrap(true);
        planText.setWrapStyleWord(true);

        var source = actions.addSource("datasources", "Datasources", null);
        var acquisition = actions.addAction(source, "field-acquisition-plan", "Field acquisition plan",
                new JScrollPane(planText), null);
        var load = actions.addAction(source, "field-values", "Load field values", acquisitionDetail, null);
        var logs = new JButton("Query logs…");
        logs.addActionListener(event -> queries.showLogs(this));
        var acquisitionTools = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        acquisitionTools.add(logs);
        acquisitionDetail.add(acquisitionValues, BorderLayout.CENTER);
        acquisitionDetail.add(acquisitionTools, BorderLayout.SOUTH);
        var transformSource = actions.addSource("transform", "Transform", null);
        var previewDetail = new JPanel(new BorderLayout());
        var previewTools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var cancelPreview = new JButton("Cancel preview");
        queries.runner().registerCancelButton(cancelPreview);
        previewTools.add(preview);
        previewTools.add(cancelPreview);
        previewTools.add(previewStatus);
        previewDetail.add(previewTools, BorderLayout.NORTH);
        previewDetail.add(previewResults, BorderLayout.CENTER);
        previewAction = actions.addAction(transformSource, "preview-population", "Preview population",
                previewDetail, null);
        preview.addActionListener(event -> previewPopulation());
        queries.runner().onRunningChanged(running -> {
            if (!running && previewing) finishPreview();
        });
        actions.setSelectionListener(selection -> {
            loadActionSelected = load.id().equals(selection.id());
            previewActionSelected = previewAction.id().source().equals(selection.id().source())
                    && previewAction.id().action().equals(selection.id().action());
            if (loadActionSelected) refreshAcquisition();
        });
        actions.expandSources();
        actions.selectAction(acquisition);
        compilePlan();
        configuration = ProjectConfigurationPanel.editWorkingConfiguration(project, transform,
                this::constructSelected, this::fieldApplied, this::refreshPreview);
        var workspace = new JSplitPane(JSplitPane.VERTICAL_SPLIT, configuration, actions);
        workspace.setResizeWeight(0.72);
        workspace.setDividerLocation(510);
        add(workspace, BorderLayout.CENTER);

        var footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.add(saveStatus);
        footer.add(save);
        add(footer, BorderLayout.SOUTH);
        updateSave();
    }

    private void constructSelected(Object construct) {
        var clazz = construct instanceof GeneratedClassModel value ? value : null;
        if (selectedClass != clazz) {
            selectedClass = clazz;
            refreshPreview();
        }
        fieldSelected(construct instanceof GeneratedFieldModel field ? field : null);
    }

    private void refreshPreview() {
        clearPreviewResults();
        previewInput = TransformationExecution.inputClass(project.projectModel(), selectedClass);
        previewStatus.setText(previewInput == null ? "Select a class with a configured transformation."
                : "Check loaded " + previewInput.className() + " instances for " + selectedClass.className() + ".");
        updateSave();
    }

    private void clearPreviewResults() {
        if (previewResults.getTabCount() == 0) return;
        if (previewActionSelected) actions.selectAction(previewAction);
        actions.clearResults(previewAction);
        previewResults.removeAll();
    }

    private void previewPopulation() {
        if (previewInput == null || saving || loadingValues || previewing || queries.runner().isRunning()
                || !configuration.resolveUnappliedEdits("previewing the population")) return;
        clearPreviewResults();
        var input = previewInput;
        var output = selectedClass;
        var query = new PreviewPopulation(project.projectModel(), input, output, domain);
        previewing = true;
        previewCompleted = false;
        configuration.setEditingEnabled(false);
        previewStatus.setText("Checking loaded " + input.className() + " instances for " + output.className() + "…");
        updateSave();
        queries.runner().run(query, result -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (previewing) showPreview(input.className(), result);
        }), failure -> {
            previewCompleted = true;
            previewStatus.setText("Population preview failed: " + failure.getMessage());
        });
    }

    private void showPreview(String input, TransformationExecution.Preview result) {
        previewCompleted = true;
        var view = SearchableView.builder(result.members())
                .sample(domain.configSample(input))
                .hiddenFields(domain.structuralFields(input))
                .fieldTypes(domain.fieldTypes(input))
                .fieldSchemas(value -> transform.renderedFieldSchema(value, input))
                .typeSchemas(domain::fieldSchema)
                .subtypeConfigs(transform.subtypeConfigs(input))
                .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                .collapsible(true).build();
        previewStatus.setText(result.members().size() + " of " + result.examined()
                + " loaded " + input + " instances match.");
        previewResults.addTab("Matching instances (" + result.members().size() + ")", view);
        actions.publishResults(previewAction, previewResults, 0, previewActionSelected);
    }

    private void finishPreview() {
        previewing = false;
        if (!previewCompleted) previewStatus.setText("Population preview cancelled.");
        configuration.setEditingEnabled(true);
        updateSave();
    }

    private record PreviewPopulation(GeneratedProjectModel model, GeneratedClassModel input,
            GeneratedClassModel output, DomainModel domain) implements Query<TransformationExecution.Preview> {
        @Override public String purpose() { return "Preview population for " + output.className(); }
        @Override public String skeleton() { return "Apply the configured filters to loaded instances"; }
        @Override public Map<String, String> parameters() {
            return Map.of("Input class", input.className(), "Output class", output.className());
        }
        @Override public TransformationExecution.Preview execute(QueryContext context) {
            context.cancellation().throwIfCancelled();
            return TransformationExecution.preview(model, output, domain.instances());
        }
        @Override public int rowCount(TransformationExecution.Preview result) { return result.members().size(); }
        @Override public String summary(TransformationExecution.Preview result) {
            return rowCount(result) + " of " + result.examined() + " loaded instances match";
        }
    }

    private void fieldSelected(GeneratedFieldModel field) {
        if (selectedField != field) acquisitionTargetCurrent = false;
        selectedField = field;
        showPlan(field);
        if (loadActionSelected) refreshAcquisition();
    }

    private void refreshAcquisition() {
        if (loadingValues || acquisitionTargetCurrent) return;
        acquisitionTargetCurrent = true;
        acquisitionValues.removeAll();
        var owner = project.projectModel().declaringClass(selectedField);
        if (owner == null) {
            acquisitionValues.add(new JLabel("Select a configured field to load its values."),
                    BorderLayout.NORTH);
        } else {
            String path = OwnedFieldSources.declaredFieldPath(owner, selectedField);
            var resolved = DomainSchemas.resolve(domain, owner.className(), path);
            var declared = FieldDefinitions.toFieldRef(selectedField.definition());
            if (selectedFieldPlan == null) {
                acquisitionValues.add(new JLabel("Review the field acquisition plan before loading values."),
                        BorderLayout.NORTH);
            } else if (resolved == null || resolved.valueKind() != declared.valueKind()
                    || resolved.valueKind() == objectview.field.FieldKind.ORDERED
                            && FieldDefinitions.fromFieldRef(resolved).type() != selectedField.type()
                    || resolved.collection() != declared.collection()
                    || !Objects.equals(resolved.targetType(), declared.targetType())) {
                acquisitionValues.add(new JLabel(owner.className() + "." + path
                        + " needs instances built with its current field definition."),
                        BorderLayout.NORTH);
            } else {
                if (valuePanel == null) {
                    valuePanel = new ValidationPanel(domain, null, queries.runner(),
                            null, null, WorkbenchPurpose.DATASOURCE);
                }
                valuePanel.useConfiguredValueTarget(owner.className(), path, project.projectModel(),
                        selectedFieldPlan, planText.getText(), this::prepareAcquisition,
                        this::acquisitionRunning);
                acquisitionValues.add(valuePanel, BorderLayout.CENTER);
            }
        }
        acquisitionValues.revalidate();
        acquisitionValues.repaint();
    }

    private boolean prepareAcquisition() {
        return !saving && !previewing && configuration.resolveUnappliedEdits("loading values")
                && valuePanel.getParent() == acquisitionValues;
    }

    private void acquisitionRunning(boolean running) {
        loadingValues = running;
        configuration.setEditingEnabled(!running);
        updateSave();
    }

    private void compilePlan() {
        plan = null;
        planProblem = null;
        try {
            plan = ModelSourceExecutionPlan.compileStored(project.projectModel(), datasources);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            planProblem = invalid.getMessage();
        }
    }

    private void showPlan(GeneratedFieldModel field) {
        selectedFieldPlan = null;
        GeneratedProjectModel model = project.projectModel();
        var owner = model.declaringClass(field);
        if (owner == null) {
            planText.setText("Select a field to see its acquisition plan.");
            return;
        }
        String path = OwnedFieldSources.declaredFieldPath(owner, field);
        var text = new StringBuilder(owner.className()).append('.').append(path).append("\n\n");
        if (plan == null) {
            text.append("Could not prepare the datasource plan.\n").append(planProblem);
        } else {
            selectedFieldPlan = plan.fieldValues(owner.className(), path);
            var steps = selectedFieldPlan.steps();
            for (var step : steps) {
                text.append(step.prepared().familyName()).append('\n');
                if (step.target().contextual()) {
                    text.append("When produced by ").append(step.target().contextClassName())
                            .append('.').append(step.target().contextFieldPath()).append('\n');
                }
                String description = step.prepared().description();
                if (!description.equals(step.prepared().familyName())) text.append(description).append('\n');
                for (var element : step.operation().configurationElements(step.binding())) {
                    appendConfiguration(text, element, "  ");
                }
                text.append('\n');
            }
            if (steps.isEmpty()) text.append("This field has no datasource binding.");
        }
        planText.setText(text.toString().stripTrailing());
        planText.setCaretPosition(0);
    }

    private static void appendConfiguration(StringBuilder text, ConfigurationElement element,
            String indent) {
        text.append(indent).append(element.caption()).append('\n');
        for (var child : element.children()) appendConfiguration(text, child, indent + "  ");
    }

    private void fieldApplied(GeneratedFieldModel field) {
        var owner = project.projectModel().declaringClass(field);
        String description = "Field configuration " + owner.className() + "."
                + OwnedFieldSources.declaredFieldPath(owner, field);
        if (!project.unsavedModelChanges().contains(description)) project.recordModelChange(description);
        compilePlan();
        acquisitionTargetCurrent = false;
        fieldSelected(field);
        updateSave();
    }

    /** Chooses among complete actions; a test may answer instead of the dialog. */
    @FunctionalInterface
    interface Choice { int ask(String title, String message, String[] options, int defaultOption); }

    private Choice choice = (title, message, options, defaultOption) ->
            Dialogs.choose(this, title, message, options, defaultOption);

    void choice(Choice value) { choice = value; }

    private java.util.function.Predicate<String> confirmPersistence = message ->
            Dialogs.confirmPersistence(this, verb(), message);

    private String verb() { return saveVerb; }

    /** Lets a test confirm the shared persistence question instead of the dialog. */
    void persistenceConfirmation(java.util.function.Predicate<String> confirm) {
        confirmPersistence = confirm;
    }

    /**
     * Save is one complete action (#535): unapplied edits are resolved first, so it never
     * writes something other than what the form shows.
     */
    private void confirmSave() {
        if (saving || loadingValues || previewing) return;
        boolean draft = configuration.hasUnappliedEdits();
        boolean applyDraft = false;
        if (draft) {
            int answer = choice.ask("Unapplied edits", configuration.editedConstructName()
                    + " has edits that are not applied.\n\nSave writes "
                    + project.modelFile().getPath() + ".",
                    new String[] {"Apply and save", "Discard draft and save applied changes",
                            "Stay"}, 2);
            if (answer == 2) return;
            applyDraft = answer == 0;
        }
        if (!applyDraft && project.unsavedModelChanges().isEmpty()) {
            if (draft) configuration.discardUnappliedEdits();
            saveStatus.setText("No applied changes to save.");
            return;
        }
        // Cancelling the file confirmation keeps the draft: nothing is applied or
        // discarded until the save is confirmed.
        if (!confirmPersistence.test("Save the applied configuration to\n"
                + project.modelFile().getPath())) return;
        if (applyDraft) {
            // A refused Apply stops here with the draft open and its reason shown.
            if (!configuration.applyUnappliedEdits()) return;
        } else if (draft) {
            configuration.discardUnappliedEdits();
        }
        startSave(null);
    }

    private java.util.function.Consumer<String> saveFailure = message ->
            Dialogs.showMessage(this, message, "Save failed", JOptionPane.ERROR_MESSAGE);

    /** Lets a test receive a save failure instead of the modal message. */
    void saveFailure(java.util.function.Consumer<String> report) { saveFailure = report; }

    private boolean showSaveSuccess = true;

    /** Tests skip the modal success message. */
    void showSaveSuccess(boolean show) { showSaveSuccess = show; }

    boolean saving() { return saving; }

    private void startSave(Runnable afterSuccess) {
        saving = true;
        // Keep the reader's place in view; only block input while the file is written.
        blockInput(true);
        saveStatus.setText("Saving " + project.modelFile().getName() + "…");
        updateSave();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                saveProject();
                return null;
            }

            @Override protected void done() {
                boolean saved = false;
                try {
                    get();
                    saved = true;
                    if (afterSuccess == null && showSaveSuccess) {
                        Dialogs.showPersistenceSuccess(UnifiedWorkbenchPanel.this, saveVerb,
                                project.projectName(), project.modelFile().getPath());
                    }
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    // The applied changes stay in memory; only the write failed.
                    saveFailure.accept(cause.getMessage());
                } finally {
                    blockInput(false);
                    saving = false;
                    updateSave();
                }
                if (saved && afterSuccess != null) afterSuccess.run();
            }
        }.execute();
    }

    void saveProject() throws Exception {
        project.writeProjectModel();
    }

    private void updateSave() {
        boolean dirty = !project.unsavedModelChanges().isEmpty();
        // Enabled whenever not saving: unapplied edits can be the only change, and the
        // form cannot announce every keystroke. Save itself says when there is nothing.
        save.setEnabled(!saving && !loadingValues && !previewing);
        preview.setEnabled(previewInput != null && !saving && !loadingValues && !previewing);
        if (loadingValues) {
            saveStatus.setText("Loading and reviewing field values");
        } else if (previewing) {
            saveStatus.setText("Previewing population for " + selectedClass.className());
        } else if (!saving) {
            saveStatus.setText(dirty ? "Applied configuration has unsaved changes" : " ");
        }
    }

    private void blockInput(boolean block) {
        JRootPane root = getRootPane();
        if (root == null) return;
        Component glass = root.getGlassPane();
        if (block && glass.getMouseListeners().length == 0) {
            glass.addMouseListener(new java.awt.event.MouseAdapter() { });
            glass.addKeyListener(new java.awt.event.KeyAdapter() { });
        }
        glass.setCursor(block ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        glass.setVisible(block);
        if (block) glass.requestFocusInWindow();
    }

    /** Lets a test answer the unapplied-edits question instead of the shared dialog. */
    void unappliedDecision(java.util.function.BiFunction<String, String,
            process.swing.Dialogs.UnappliedEdits> decision) {
        configuration.unappliedDecision(decision);
    }

    /** Whether the open editor holds unapplied edits. */
    boolean hasUnappliedEdits() { return configuration.hasUnappliedEdits(); }

    /**
     * Close is one decision among complete actions (#535): with unapplied edits, Apply,
     * save and close / Discard changes and close / Stay; with only applied unsaved
     * changes, Save and close / Discard changes and close / Stay. Stay is the default.
     * A refused Apply or a failed save keeps the window and the edits open.
     */
    public void requestClose(Runnable closeWindow) {
        if (saving) return;
        if (previewing) {
            SwingCancellationConfirmation.requestForVisibleRun(this, queries.runner()::cancel);
            return;
        }
        if (loadingValues) {
            SwingCancellationConfirmation.requestForVisibleRun(this, valuePanel::cancelFindData);
            return;
        }
        boolean draft = configuration.hasUnappliedEdits();
        boolean unsaved = !project.unsavedModelChanges().isEmpty();
        if (!draft && !unsaved) {
            closeWindow.run();
            return;
        }
        String file = project.modelFile().getPath();
        String message = draft
                ? configuration.editedConstructName() + " has edits that are not applied"
                        + (unsaved ? ", and applied changes are not saved" : "")
                        + ".\n\nSave writes " + file + "."
                : "The applied changes to " + file + " are not saved.";
        String[] options = {draft ? "Apply, save and close" : "Save and close",
                "Discard changes and close", "Stay"};
        int answer = choice.ask("Unsaved project changes", message, options, 2);
        if (answer == 1) {
            closeWindow.run();
        } else if (answer == 0) {
            if (draft && !configuration.applyUnappliedEdits()) return;
            if (project.unsavedModelChanges().isEmpty()) {
                closeWindow.run();
                return;
            }
            startSave(closeWindow);
        }
    }

    @Override public void close() {
        configuration.close();
        queries.logs().resetForProjectSwitch();
    }
}
