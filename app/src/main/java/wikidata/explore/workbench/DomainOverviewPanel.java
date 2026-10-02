package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.BuildOperation;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Project-level configuration and status shown for the tree root. */
final class DomainOverviewPanel extends JPanel {
    record Status(boolean modelSaved, boolean snapshotSaved, int generatedObjects) {
        static final Status EMPTY = new Status(false, false, 0);
    }

    private final GeneratedProjectModel project;
    private Supplier<Status> status = () -> Status.EMPTY;
    private Consumer<Void> afterChange = ignored -> { };
    private final JLabel projectName = new JLabel();
    private final JComboBox<GeneratedProjectModel.ProjectKind> projectKind =
            new JComboBox<>(GeneratedProjectModel.ProjectKind.values());
    private final JLabel rootClass = new JLabel();
    private final JLabel classes = new JLabel();
    private final JLabel selections = new JLabel();
    private final JLabel kinds = new JLabel();
    private final JLabel modelFile = new JLabel();
    private final JLabel snapshot = new JLabel();
    private final JLabel generated = new JLabel();
    /** The project's build, in order: what a build run executes, step by step. */
    private final OrderedChoiceList<Step> build = new OrderedChoiceList<>(true);
    private boolean refreshing;

    /** One row of the build: what it does and the graph it acts on. Two rows that do the
     *  same thing to the same graph are one step, so the chooser offers each once. */
    record Step(BuildOperation.Kind kind, String targetDeclarationId) { }

    DomainOverviewPanel(GeneratedProjectModel project) {
        super(new GridBagLayout());
        this.project = project;
        projectKind.setName("project.kind");
        projectKind.addActionListener(event -> changeKind());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        JLabel title = new JLabel("Project overview");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        GridBagUtils.wideRow(this, row++, title);
        GridBagUtils.labeledRow(this, c, row++, "Name:", projectName);
        GridBagUtils.labeledRow(this, c, row++, "Kind:", projectKind);
        GridBagUtils.labeledRow(this, c, row++, "Generation root:", rootClass);
        GridBagUtils.labeledRow(this, c, row++, "Classes:", classes);
        GridBagUtils.labeledRow(this, c, row++, "Vocabularies / populations:", selections);
        GridBagUtils.labeledRow(this, c, row++, "Entity-kind rules:", kinds);
        GridBagUtils.labeledRow(this, c, row++, "Model:", modelFile);
        GridBagUtils.labeledRow(this, c, row++, "Snapshot:", snapshot);
        GridBagUtils.labeledRow(this, c, row++, "Current generated objects:", generated);
        build.setName("project.build");
        build.title("Build");
        build.describe(step -> new BuildOperation(step.kind(), step.targetDeclarationId())
                .describe(project));
        build.onChange(this::changeBuild);
        GridBagUtils.wideRow(this, row++, build);
        GridBagUtils.wideRow(this, row++, new JLabel(
                "Select a class, field, or vocabulary below the domain to configure it."));
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = row;
        filler.weighty = 1;
        add(new JLabel(), filler);
        refresh();
    }

    void status(Supplier<Status> supplier) {
        status = supplier == null ? () -> Status.EMPTY : supplier;
        refresh();
    }

    void afterChange(Consumer<Void> consumer) {
        afterChange = consumer == null ? ignored -> { } : consumer;
    }

    private void changeKind() {
        if (refreshing) return;
        GeneratedProjectModel.ProjectKind selected =
                (GeneratedProjectModel.ProjectKind) projectKind.getSelectedItem();
        if (selected == null || selected == project.projectKind()) return;
        project.projectKind(selected);
        refresh();
        afterChange.accept(null);
    }

    /**
     * Writes the listed steps as the project's build. A step still listed keeps the
     * operation it was, declaration id and all; only a newly added one is new.
     */
    private void changeBuild() {
        if (refreshing) return;
        java.util.List<BuildOperation> existing = project.buildOperations();
        java.util.List<BuildOperation> next = new java.util.ArrayList<>();
        for (Step step : build.chosen()) {
            next.add(existing.stream().filter(operation -> stepOf(operation).equals(step))
                    .findFirst()
                    .orElseGet(() -> new BuildOperation(step.kind(), step.targetDeclarationId())));
        }
        project.buildOperations(next);
        afterChange.accept(null);
    }

    private static Step stepOf(BuildOperation operation) {
        return new Step(operation.kind(),
                operation.kind().targetsGraph() ? operation.targetDeclarationId() : "");
    }

    /** Every step the project could take: generate, run and apply each graph, save. */
    private java.util.List<Step> possibleSteps() {
        java.util.List<Step> steps = new java.util.ArrayList<>();
        steps.add(new Step(BuildOperation.Kind.GENERATE_PROJECT, ""));
        for (GeneratedClassModel graph : project.graphClasses()) {
            if (graph == null || graph.isImported()) continue;
            steps.add(new Step(BuildOperation.Kind.RUN_GRAPH_CONSTRAINT, graph.declarationId()));
            steps.add(new Step(BuildOperation.Kind.APPLY_GRAPH_DECISIONS, graph.declarationId()));
        }
        steps.add(new Step(BuildOperation.Kind.SAVE_PROJECT_RESULT, ""));
        return steps;
    }

    void refresh() {
        refreshing = true;
        try {
            Status current = status.get();
            if (current == null) current = Status.EMPTY;
            projectName.setText(project.name());
            projectKind.setSelectedItem(project.projectKind());
            rootClass.setText(project.rootClass() == null
                    ? "—" : project.rootClass().className());
            classes.setText(Integer.toString(project.classes().size()));
            selections.setText(Integer.toString(project.selections().size()));
            long configuredKinds = project.entityKindRules().stream()
                    .filter(rule -> rule != null && rule.isConfigured()).count();
            kinds.setText(configuredKinds + " configured / " + project.entityKindRules().size());
            modelFile.setText(current.modelSaved() ? "saved" : "not saved yet");
            snapshot.setText(current.snapshotSaved() ? "saved" : "not generated yet");
            generated.setText(Integer.toString(current.generatedObjects()));
            build.show(project.buildOperations().stream()
                    .map(DomainOverviewPanel::stepOf).toList(), possibleSteps());
        } finally {
            refreshing = false;
        }
    }
}
