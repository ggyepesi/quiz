package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.codegen.GeneratedViewableSourceGenerator;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Structural editor for an Owned class. Ownership sites remain field-defined. */
final class OwnedClassPanel extends JPanel {

    private static final String NO_BASE = "(none)";
    private final GeneratedProjectModel project;
    private final JTextField className = new JTextField(18);
    private final JTextField alias = new JTextField(18);
    private final JComboBox<String> baseClass = new JComboBox<>();
    private final JLabel sites = new JLabel(" ");
    private final JButton apply = new JButton("Apply owned class");
    private GeneratedClassModel clazz;
    private Consumer<Void> afterChange = ignored -> {};

    OwnedClassPanel(GeneratedProjectModel project) {
        super(new BorderLayout());
        this.project = project;
        buildUi();
    }

    void afterChange(Consumer<Void> consumer) {
        afterChange = consumer == null ? ignored -> {} : consumer;
    }

    void edit(GeneratedClassModel value) {
        clazz = value;
        className.setText(value == null ? "" : value.className());
        // The name of an adopted class belongs to the model it came from, so it is
        // readable but not typeable here. Set on every edit so selecting a class
        // authored here restores it.
        className.setEditable(value == null || !value.nameLocked());
        alias.setText(value == null ? "" : value.alias());
        baseClass.removeAllItems();
        baseClass.addItem(NO_BASE);
        for (GeneratedClassModel candidate : project.classes()) {
            if (candidate != null && value != null
                    && candidate.ownedClass()
                    && !candidate.className().equals(value.className())) {
                baseClass.addItem(candidate.className());
            }
        }
        String base = value == null ? "" : value.baseClassName();
        baseClass.setSelectedItem(base.isBlank() ? NO_BASE : base);
        refreshSites();
    }

    void applyEdits() {
        if (clazz == null) return;
        String previousName = clazz.className();
        String typedName = className.getText() == null ? "" : className.getText().trim();
        if (typedName.isBlank()) {
            renameError("A class name is required.");
            className.setText(clazz.className());
        } else {
            String requested = GeneratedViewableSourceGenerator
                    .sanitizeClassName(typedName);
            if (!project.renameClass(previousName, requested)) {
                renameError("A class or vocabulary/population named '" + requested
                        + "' already exists.");
                className.setText(clazz.className());
            }
        }
        className.setText(clazz.className());
        clazz.alias(alias.getText());
        Object selectedBase = baseClass.getSelectedItem();
        clazz.baseClassName(selectedBase == null || NO_BASE.equals(selectedBase)
                ? "" : selectedBase.toString());
        clazz.discriminatorPid("");
        clazz.discriminatorQid("");
        clazz.ownedClass(true);
        refreshSites();
        afterChange.accept(null);
    }

    private void renameError(String message) {
        JOptionPane.showMessageDialog(this, message, "Cannot rename class",
                JOptionPane.WARNING_MESSAGE);
    }

    private void refreshSites() {
        String shown = MembershipPattern.ownedBy(clazz, project).stream()
                .map(site -> site.ownerClass() + "." + site.fieldName())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none yet");
        sites.setText("Producing fields: " + shown);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        GridBagUtils.wideRow(form, row++, new JLabel(
                "<html><b>Owned class</b> — instances are created by fields that target this class.</html>"));
        GridBagUtils.labeledRow(form, c, row++, "Class name:", className);
        GridBagUtils.labeledRow(form, c, row++, "Alias:", alias);
        GridBagUtils.labeledRow(form, c, row++, "Extends:", baseClass);
        GridBagUtils.wideRow(form, row++, sites);
        GridBagUtils.wideRow(form, row++, new JLabel(
                "<html>The owner is not configured here. Add an ENTITY field to the "
                        + "owner class and select this class as its target.</html>"));
        GridBagUtils.wideRow(form, row, apply);
        apply.addActionListener(event -> applyEdits());
        add(form, BorderLayout.NORTH);
    }
}
