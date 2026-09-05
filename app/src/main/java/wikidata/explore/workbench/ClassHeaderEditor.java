package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.codegen.GeneratedViewableSourceGenerator;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.ClassExtensionRules;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Supplier;

/**
 * The facts a class has whatever kind it is: its name, its display alias, and what it
 * extends.
 *
 * <p>Four kind editors each decided independently how many of these to show. A Statement
 * class had a name and no alias or base; an Aggregate class had none of the three, so it
 * could not be renamed at all — {@code RenameClass} is used by the Source, Statement and
 * Owned panels and by nothing else, which is why renaming an aggregate was not merely
 * hidden but absent. Nothing in the model or the validator restricts alias or a base by
 * kind. Which bases are legal is a model rule shared with validation, not a choice each
 * kind editor reconstructs.
 *
 * <p>These are class facts, not kind facts. Each panel keeps only what its kind adds.
 */
final class ClassHeaderEditor extends JPanel {

    private static final String NO_BASE = "(none)";

    private final JTextField className = new JTextField(18);
    private final JTextField alias = new JTextField(18);
    private final JComboBox<String> baseClass = new JComboBox<>();

    private final Supplier<GeneratedProjectModel> project;
    private GeneratedClassModel clazz;

    /**
     * @param project the model that owns both the edited class and its possible bases
     */
    ClassHeaderEditor(Supplier<GeneratedProjectModel> project) {
        super(new GridBagLayout());
        this.project = project == null ? () -> null : project;
        buildUi();
    }

    private void buildUi() {
        setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        className.setToolTipText(
                "The name everything references. Renaming rebinds those references.");
        GridBagUtils.labeledRow(this, c, 0, "Class name:", className);
        alias.setToolTipText("<html>Display alias: what the UI shows for this class "
                + "instead of its name. Pure presentation — the class name stays the "
                + "identity everything references, so aliasing never breaks the "
                + "model.</html>");
        GridBagUtils.labeledRow(this, c, 1, "Alias:", alias);
        baseClass.setToolTipText("<html>Extend another class: this class inherits its "
                + "fields and adds its own.</html>");
        GridBagUtils.labeledRow(this, c, 2, "Extends:", baseClass);
    }

    void show(GeneratedClassModel value) {
        clazz = value;
        className.setText(value == null ? "" : value.className());
        alias.setText(value == null ? "" : value.alias());

        baseClass.removeAllItems();
        baseClass.addItem(NO_BASE);
        String self = value == null ? "" : value.className();
        for (GeneratedClassModel candidate :
                ClassExtensionRules.candidates(project.get(), value)) {
            String name = candidate.className();
            if (!name.isBlank() && !name.equals(self)) baseClass.addItem(name);
        }
        String base = value == null ? "" : value.baseClassName();
        baseClass.setSelectedItem(base.isBlank() ? NO_BASE : base);

    }

    /**
     * Writes the name, alias and base back.
     *
     * <p>A rename can be refused — by a name already taken — and then the field is put
     * back to what the class is still called, rather than left showing a name nothing
     * answers to.
     */
    void applyEdits() {
        if (clazz == null || clazz.isImported()) return;
        String typed = className.getText() == null ? "" : className.getText().trim();
        if (typed.isBlank()) {
            refuse("A class name is required.");
        } else if (!typed.equals(clazz.className())) {
            String requested =
                    GeneratedViewableSourceGenerator.sanitizeClassName(typed);
            GeneratedProjectModel owner = project.get();
            if (owner == null || !owner.renameClass(clazz.className(), requested)) {
                refuse("A class or vocabulary/population named '" + requested
                        + "' already exists.");
            }
        }
        className.setText(clazz.className());
        clazz.alias(alias.getText());
        Object base = baseClass.getSelectedItem();
        clazz.baseClassName(
                base == null || NO_BASE.equals(base) ? "" : base.toString());
    }

    private void refuse(String message) {
        JOptionPane.showMessageDialog(this, message, "Cannot rename class",
                JOptionPane.WARNING_MESSAGE);
        className.setText(clazz.className());
    }
}
