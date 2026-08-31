package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;

/** Read-only configuration landing page for the domain tree node. */
final class DomainOverviewPanel extends JPanel {
    record Status(boolean modelSaved, boolean snapshotSaved, int generatedObjects) {
        static final Status EMPTY = new Status(false, false, 0);
    }

    private final GeneratedProjectModel project;
    private Supplier<Status> status = () -> Status.EMPTY;
    private final JLabel domain = new JLabel();
    private final JLabel rootClass = new JLabel();
    private final JLabel classes = new JLabel();
    private final JLabel selections = new JLabel();
    private final JLabel kinds = new JLabel();
    private final JLabel imports = new JLabel();
    private final JLabel modelFile = new JLabel();
    private final JLabel snapshot = new JLabel();
    private final JLabel generated = new JLabel();

    DomainOverviewPanel(GeneratedProjectModel project) {
        super(new GridBagLayout());
        this.project = project;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        JLabel title = new JLabel("Domain overview");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        GridBagUtils.wideRow(this, row++, title);
        GridBagUtils.labeledRow(this, c, row++, "Domain:", domain);
        GridBagUtils.labeledRow(this, c, row++, "Root class:", rootClass);
        GridBagUtils.labeledRow(this, c, row++, "Classes:", classes);
        GridBagUtils.labeledRow(this, c, row++, "Vocabularies / populations:", selections);
        GridBagUtils.labeledRow(this, c, row++, "Entity-kind rules:", kinds);
        GridBagUtils.labeledRow(this, c, row++, "Shared modules:", imports);
        GridBagUtils.labeledRow(this, c, row++, "Model:", modelFile);
        GridBagUtils.labeledRow(this, c, row++, "Snapshot:", snapshot);
        GridBagUtils.labeledRow(this, c, row++, "Current generated objects:", generated);
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

    void refresh() {
        Status current = status.get();
        if (current == null) current = Status.EMPTY;
        domain.setText(project.name());
        rootClass.setText(project.rootClass() == null ? "—" : project.rootClass().className());
        classes.setText(Integer.toString(project.classes().size()));
        selections.setText(Integer.toString(project.selections().size()));
        long configuredKinds = project.entityKindRules().stream()
                .filter(rule -> rule != null && rule.isConfigured()).count();
        kinds.setText(configuredKinds + " configured / " + project.entityKindRules().size());
        imports.setText(project.imports().isEmpty() ? "none"
                : project.imports().stream().map(wikidata.explore.model.ModelModuleImport::coordinate)
                        .collect(java.util.stream.Collectors.joining(", ")));
        modelFile.setText(current.modelSaved() ? "saved" : "not saved yet");
        snapshot.setText(current.snapshotSaved() ? "saved" : "not generated yet");
        generated.setText(Integer.toString(current.generatedObjects()));
    }
}
