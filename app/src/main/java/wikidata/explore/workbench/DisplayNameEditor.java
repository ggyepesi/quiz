package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * How a class's instances are named: by a label, by one field, or by a template.
 *
 * <p>The same three-mode question was asked four ways. This panel's kind asked it whole;
 * the statement editor offered a field box that showed a template as an uneditable string
 * because it had no control for one; the aggregate editor had a row of field checkboxes
 * it composed a template FROM and read back by substring, so a template written as
 * {@code Best {category}} came back as {@code {category}} and any separator but an em
 * dash was silently rewritten; and the owned editor asked it not at all.
 *
 * <p>Every kind gets all three modes. An owned part is an instance of its class and is
 * told apart by that class exactly like any other instance — ownership says how the data
 * is produced, not what the instances are called. What differs by kind is only what LABEL
 * resolves to, which {@link CanonicalEditorPolicy#labelSource} answers once.
 */
final class DisplayNameEditor extends JPanel {

    private static final String LABEL = "Label";
    private static final String FIELD = "Field";
    private static final String TEMPLATE = "Template";

    private final JComboBox<String> modeBox =
            new JComboBox<>(new String[]{LABEL, FIELD, TEMPLATE});
    private final JComboBox<String> fieldBox = new JComboBox<>();
    private final JTextField templateField = new JTextField(18);
    private final JLabel hint = new JLabel(" ");

    private GeneratedClassModel clazz;
    private Runnable onChange = () -> { };

    DisplayNameEditor() {
        super(new GridBagLayout());
        buildUi();
    }

    private void buildUi() {
        setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        modeBox.setToolTipText("How to make the display name: the name this class's "
                + "instances already have, a single field's value, or a template.");
        GridBagUtils.labeledRow(this, c, 0, "Display name:", modeBox);

        fieldBox.setToolTipText("Single-valued field to show as the label "
                + "(a reference shows its own name).");
        GridBagUtils.labeledRow(this, c, 1, "  from field:", fieldBox);

        templateField.setToolTipText("e.g. {nominee} · {category} {year} "
                + "— {field} is replaced by that field's label.");
        GridBagUtils.labeledRow(this, c, 2, "  template:", templateField);

        hint.setForeground(new Color(0xB00020));
        GridBagUtils.wideRow(this, 3, hint);

        modeBox.addActionListener(event -> {
            refreshEnablement();
            onChange.run();
        });
    }

    /** Run after the reader changes the mode, so an owner can refresh what it shows. */
    void onChange(Runnable listener) {
        onChange = listener == null ? () -> { } : listener;
    }

    void show(GeneratedClassModel value) {
        clazz = value;

        // Naming and identifying are different questions. The statement editor offered
        // the canonical-KEY candidates here, which is a rule about what identifies an
        // instance answering what may name one — two facts that merely agree. A field
        // that holds one value can name its instance whether or not it keys it.
        fieldBox.removeAllItems();
        fieldBox.addItem("");
        if (value != null) {
            for (GeneratedFieldModel field : value.fields()) {
                if (field != null && !field.isNameField()
                        && field.cardinality() != FieldCardinality.COLLECTION) {
                    fieldBox.addItem(field.name());
                }
            }
        }

        CanonicalSpec spec = value == null ? new CanonicalSpec() : value.canonical();
        modeBox.setSelectedItem(switch (spec.displayNameMode()) {
            case FIELD -> FIELD;
            case TEMPLATE -> TEMPLATE;
            case LABEL -> LABEL;
        });
        fieldBox.setSelectedItem(spec.displayNameField());
        templateField.setText(spec.displayNameTemplate());
        refreshEnablement();
    }

    CanonicalSpec.DisplayNameMode mode() {
        Object selected = modeBox.getSelectedItem();
        if (TEMPLATE.equals(selected)) return CanonicalSpec.DisplayNameMode.TEMPLATE;
        if (FIELD.equals(selected)) return CanonicalSpec.DisplayNameMode.FIELD;
        return CanonicalSpec.DisplayNameMode.LABEL;
    }

    /** Writes the mode and the value that mode uses back onto the class. */
    void applyEdits() {
        if (clazz == null) return;
        Object field = fieldBox.getSelectedItem();
        clazz.canonical(CanonicalEditorPolicy.spec(
                clazz.classKind(), mode(),
                field == null ? "" : field.toString(),
                templateField.getText(), clazz.canonical()));
        refreshEnablement();
    }

    /** What is wrong with the current choice, or blank when nothing is. */
    String warning() {
        if (clazz == null) return "";
        return switch (mode()) {
            case FIELD -> fieldBox.getItemCount() <= 1
                    ? "No single-valued field to use as the display name — add one or "
                            + "use a template."
                    : "";
            case TEMPLATE -> templateField.getText().isBlank()
                    ? "Template is empty — the display name won't resolve." : "";
            case LABEL -> CanonicalEditorPolicy.labelSource(clazz.classKind()).isBlank()
                    ? "These instances have no name of their own — pick Field or "
                            + "Template."
                    : "";
        };
    }

    private void refreshEnablement() {
        boolean hasClass = clazz != null;
        CanonicalSpec.DisplayNameMode mode = mode();
        modeBox.setEnabled(hasClass);
        fieldBox.setEnabled(hasClass && mode == CanonicalSpec.DisplayNameMode.FIELD);
        templateField.setEnabled(
                hasClass && mode == CanonicalSpec.DisplayNameMode.TEMPLATE);
        String source = hasClass
                ? CanonicalEditorPolicy.labelSource(clazz.classKind()) : "";
        modeBox.setToolTipText(source.isBlank()
                ? "How to make the display name: a single field's value, or a template."
                : "How to make the display name: " + source + ", a single field's "
                        + "value, or a template.");
        String warning = warning();
        hint.setText(warning.isBlank() ? " " : warning);
    }
}
