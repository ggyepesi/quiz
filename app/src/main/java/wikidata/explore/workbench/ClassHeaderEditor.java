package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.codegen.GeneratedViewableSourceGenerator;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
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
 * kind: the one kind-specific rule is that an Owned class may extend only another Owned
 * class, which constrains what a base may BE, and is expressed here as the candidate list
 * a caller supplies.
 *
 * <p>These are class facts, not kind facts. Each panel keeps only what its kind adds.
 */
final class ClassHeaderEditor extends JPanel {

    private static final String NO_BASE = "(none)";

    private final JTextField className = new JTextField(18);
    private final JTextField alias = new JTextField(18);
    private final JComboBox<String> baseClass = new JComboBox<>();

    /**
     * Says the class belongs to another model, above controls that are all disabled.
     *
     * <p>Carried here rather than left to each panel: without it a disabled editor reads
     * as broken rather than as owned elsewhere, and an alias is exactly what an imported
     * class is for — reading as "Structured name" locally without a rename that would
     * break every reference to it.
     */
    private final JLabel importedFrom = new JLabel(" ");

    private final GeneratedProjectModel project;
    private final Supplier<List<String>> baseCandidates;
    private GeneratedClassModel clazz;

    /**
     * @param baseCandidates which classes this kind may extend — the Owned rule, and any
     *                       other, said by the caller that knows its kind rather than by
     *                       a switch in here
     */
    ClassHeaderEditor(GeneratedProjectModel project, Supplier<List<String>> baseCandidates) {
        super(new GridBagLayout());
        this.project = project;
        this.baseCandidates = baseCandidates == null ? List::of : baseCandidates;
        buildUi();
    }

    private void buildUi() {
        setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        importedFrom.setVisible(false);
        GridBagUtils.wideRow(this, 0, importedFrom);
        className.setToolTipText(
                "The name everything references. Renaming rebinds those references.");
        GridBagUtils.labeledRow(this, c, 1, "Class name:", className);
        alias.setToolTipText("<html>Display alias: what the UI shows for this class "
                + "instead of its name. Pure presentation — the class name stays the "
                + "identity everything references, so aliasing never breaks the "
                + "model.</html>");
        GridBagUtils.labeledRow(this, c, 2, "Alias:", alias);
        baseClass.setToolTipText("<html>Extend another class: this class inherits its "
                + "fields and adds its own.</html>");
        GridBagUtils.labeledRow(this, c, 3, "Extends:", baseClass);
    }

    void show(GeneratedClassModel value) {
        clazz = value;
        className.setText(value == null ? "" : value.className());
        alias.setText(value == null ? "" : value.alias());

        baseClass.removeAllItems();
        baseClass.addItem(NO_BASE);
        String self = value == null ? "" : value.className();
        for (String candidate : baseCandidates.get()) {
            if (candidate != null && !candidate.isBlank() && !candidate.equals(self)) {
                baseClass.addItem(candidate);
            }
        }
        String base = value == null ? "" : value.baseClassName();
        baseClass.setSelectedItem(base.isBlank() ? NO_BASE : base);

        boolean imported = value != null && value.isImported();
        importedFrom.setVisible(imported);
        if (imported) {
            importedFrom.setText("<html><i>Imported from <b>" + value.importedFrom()
                    + "</b> — edited in the model that owns it.</i></html>");
        }
        EditableComponents.setEditable(className, !imported);
        EditableComponents.setEditable(alias, !imported);
        EditableComponents.setEditable(baseClass, !imported);
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
            if (!project.renameClass(clazz.className(), requested)) {
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
