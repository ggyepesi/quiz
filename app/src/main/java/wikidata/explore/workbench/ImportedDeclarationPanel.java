package wikidata.explore.workbench;

import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.ModelClassPresentationOverlay;
import wikidata.explore.model.CanonicalSpec;

import javax.swing.*;
import java.awt.*;

/** Read-only explanation for a declaration owned by a pinned shared module. */
final class ImportedDeclarationPanel extends JPanel {
    private final JLabel title = new JLabel();
    private final JLabel origin = new JLabel();
    private final JTextArea structure = new JTextArea();
    private final GeneratedProjectModel model;
    private final JPanel overlay = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    private final JComboBox<CanonicalSpec.DisplayNameMode> displayMode =
            new JComboBox<>(CanonicalSpec.DisplayNameMode.values());
    private final JComboBox<String> displayField = new JComboBox<>();
    private final JTextField displayTemplate = new JTextField(18);
    private final JButton apply = new JButton("Apply presentation");
    private Runnable afterChange = () -> { };
    private SingleRootClassModelPanel.ImportedClass selectedClass;

    ImportedDeclarationPanel(GeneratedProjectModel model) {
        super(new BorderLayout(8, 8));
        this.model = model;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        header.add(title);
        header.add(origin);
        header.add(new JLabel("Structure is owned by the shared module and is read-only here."));
        overlay.add(new JLabel("Display name:"));
        overlay.add(displayMode);
        overlay.add(displayField);
        overlay.add(displayTemplate);
        overlay.add(apply);
        header.add(overlay);
        structure.setEditable(false);
        structure.setLineWrap(true);
        structure.setWrapStyleWord(true);
        add(header, BorderLayout.NORTH);
        add(new JScrollPane(structure), BorderLayout.CENTER);
        displayMode.addActionListener(e -> updateOverlayControls());
        apply.addActionListener(e -> applyOverlay());
    }

    void afterChange(Runnable action) { afterChange = action == null ? () -> { } : action; }

    void edit(Object selected) {
        if (selected instanceof SingleRootClassModelPanel.ImportedClass imported) {
            selectedClass = imported;
            title.setText("Imported class: " + imported.declaration().className());
            origin.setText("Origin: " + imported.origin().coordinate());
            StringBuilder body = new StringBuilder("Kind: ")
                    .append(imported.declaration().classKind());
            if (imported.declaration().hasBase()) {
                body.append("\nExtends: ").append(imported.declaration().baseClassName());
            }
            body.append("\n\nFields:");
            for (GeneratedFieldModel field : imported.declaration().fields()) {
                body.append("\n• ").append(describe(field));
            }
            structure.setText(body.toString());
            editOverlay(imported);
        } else if (selected instanceof SingleRootClassModelPanel.ImportedField imported) {
            selectedClass = null;
            overlay.setVisible(false);
            title.setText("Imported field: " + imported.owner().declaration().className()
                    + "." + imported.declaration().name());
            origin.setText("Origin: " + imported.owner().origin().coordinate());
            structure.setText(describe(imported.declaration()));
        } else if (selected instanceof SingleRootClassModelPanel.ImportedSelection imported) {
            selectedClass = null;
            overlay.setVisible(false);
            title.setText("Imported vocabulary / population: "
                    + imported.declaration().name());
            origin.setText("Origin: " + imported.origin().coordinate());
            structure.setText("Kind: " + imported.declaration().kind());
        }
        structure.setCaretPosition(0);
    }

    private void editOverlay(SingleRootClassModelPanel.ImportedClass imported) {
        overlay.setVisible(true);
        displayField.removeAllItems();
        displayField.addItem("");
        imported.declaration().fields().stream()
                .filter(field -> field.cardinality()
                        != wikidata.explore.model.FieldCardinality.COLLECTION)
                .map(GeneratedFieldModel::name).forEach(displayField::addItem);
        ModelClassPresentationOverlay saved = model.modulePresentationOverlay(
                imported.declaration().declarationId());
        CanonicalSpec source = imported.declaration().canonical();
        displayMode.setSelectedItem(saved == null
                ? source.displayNameMode() : saved.displayNameMode());
        displayField.setSelectedItem(saved == null
                ? source.displayNameField() : saved.displayNameField());
        displayTemplate.setText(saved == null
                ? source.displayNameTemplate() : saved.displayNameTemplate());
        updateOverlayControls();
    }

    private void updateOverlayControls() {
        CanonicalSpec.DisplayNameMode mode =
                (CanonicalSpec.DisplayNameMode) displayMode.getSelectedItem();
        displayField.setVisible(mode == CanonicalSpec.DisplayNameMode.FIELD);
        displayTemplate.setVisible(mode == CanonicalSpec.DisplayNameMode.TEMPLATE);
        overlay.revalidate();
    }

    private void applyOverlay() {
        if (selectedClass == null) return;
        model.replaceModulePresentationOverlay(new ModelClassPresentationOverlay(
                selectedClass.declaration().declarationId(),
                (CanonicalSpec.DisplayNameMode) displayMode.getSelectedItem(),
                String.valueOf(displayField.getSelectedItem()), displayTemplate.getText()));
        afterChange.run();
    }

    private static String describe(GeneratedFieldModel field) {
        StringBuilder text = new StringBuilder(field.name()).append(" — ")
                .append(field.cardinality()).append(' ').append(field.type());
        if (!field.entityClassName().isBlank()) {
            text.append(" of ").append(field.entityClassName());
        }
        if (!field.mapping().propertyPid().isBlank()) {
            text.append(" · ").append(field.mapping().propertyPid());
        }
        return text.toString();
    }
}
