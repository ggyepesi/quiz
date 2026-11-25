package domain.workbench.modelbuilder.ui;

import objectview.utils.swing.GridBagUtils;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.ClassExtensionRules;
import domain.config.GroupClassSpec;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The class settings shared by all production kinds.
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
    private static final String NOT_A_GROUP = "(not a group)";

    private final JTextField className = new JTextField(18);
    private final JTextField alias = new JTextField(18);
    private final JComboBox<String> baseClass = new JComboBox<>();
    private final JLabel groupMembersLabel = new JLabel("Group members field:");
    private final JComboBox<String> groupMembersField = new JComboBox<>();

    private final Supplier<GeneratedProjectModel> project;
    private Consumer<Boolean> baseSelectionChanged = ignored -> { };
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
                "The name everything references. Use Rename class in the class tree to change it.");
        EditableComponents.keepReadOnly(className);
        GridBagUtils.labeledRow(this, c, 0, "Class name:", className);
        alias.setToolTipText("<html>Display alias: what the UI shows for this class "
                + "instead of its name. Pure presentation — the class name stays the "
                + "identity everything references, so aliasing never breaks the "
                + "model.</html>");
        GridBagUtils.labeledRow(this, c, 1, "Alias:", alias);
        baseClass.setToolTipText("<html>Extend another class: this class inherits its "
                + "fields and adds its own.</html>");
        baseClass.addActionListener(event ->
                baseSelectionChanged.accept(hasSelectedBase()));
        GridBagUtils.labeledRow(this, c, 2, "Extends:", baseClass);

        groupMembersField.setName("class.groupMembersField");
        groupMembersField.setToolTipText("Use each instance of this class as a group, "
                + "taking its members from the selected collection field.");
        GridBagUtils.labeledRow(this, c, 3, groupMembersLabel, groupMembersField);
    }

    void onBaseSelectionChanged(Consumer<Boolean> listener) {
        baseSelectionChanged = listener == null ? ignored -> { } : listener;
    }

    boolean hasSelectedBase() {
        Object selected = baseClass.getSelectedItem();
        return selected != null && !NO_BASE.equals(selected);
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

        var memberFields = value == null ? java.util.List.<String>of()
                : value.effectiveFields(project.get()).stream()
                        .filter(GroupClassSpec::acceptsMembersField)
                        .map(domain.config.GeneratedFieldModel::name).toList();
        String configured = value == null || value.groupSpec() == null
                ? NOT_A_GROUP : value.groupSpec().membersField();
        groupMembersField.removeAllItems();
        groupMembersField.addItem(NOT_A_GROUP);
        memberFields.forEach(groupMembersField::addItem);
        if (!NOT_A_GROUP.equals(configured) && !memberFields.contains(configured)) {
            groupMembersField.addItem(configured);
        }
        groupMembersField.setSelectedItem(configured);
        boolean canShowGroup = groupMembersField.getItemCount() > 1;
        groupMembersLabel.setVisible(canShowGroup);
        groupMembersField.setVisible(canShowGroup);
    }

    /**
     * Writes the editable alias and base back. The class name is displayed here but
     * authored only by the tree's explicit Rename class action.
     */
    void applyEdits() {
        if (clazz == null || clazz.isImported()) return;
        className.setText(clazz.className());
        clazz.alias(alias.getText());
        Object base = baseClass.getSelectedItem();
        clazz.baseClassName(
                base == null || NO_BASE.equals(base) ? "" : base.toString());
        String members = (String) groupMembersField.getSelectedItem();
        clazz.groupSpec(members == null || NOT_A_GROUP.equals(members)
                ? null : new GroupClassSpec(members));
    }

}
