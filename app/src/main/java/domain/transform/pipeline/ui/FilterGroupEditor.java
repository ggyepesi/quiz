package domain.transform.pipeline.ui;

import domain.DomainField;
import domain.transform.FilterCondition;
import domain.transform.ui.TransformController;
import objectview.utils.swing.WrapLayout;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Edit one field condition and explicitly turn it into a named group. */
final class FilterGroupEditor extends JPanel {
    private final BiConsumer<String, FilterCondition> filterGroupCreator;
    private final FilterConditionEditor filterCondition;
    private final JButton addFilterGroup = new JButton("Add filter group");
    private Supplier<String> filterGroupName = this::askFilterGroupName;
    private boolean creationEnabled = true;
    private DomainField field;

    FilterGroupEditor(TransformController controller,
            BiConsumer<String, FilterCondition> filterGroupCreator) {
        this.filterGroupCreator = filterGroupCreator;
        this.filterCondition = new FilterConditionEditor(controller::candidateValueSelection,
                controller::sampleFieldValue);
        addFilterGroup.setToolTipText(
                "Make the instances matching this condition a named group");
        addFilterGroup.setName("filter.addGroup");
        addFilterGroup.setEnabled(false);
        addFilterGroup.addActionListener(e -> requestAddFilterGroup());

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        filterCondition.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(filterCondition);

        // This action needs its own allocated row. As the final item in the condition's
        // FlowLayout it wrapped at the real left-pane width, but BorderLayout.NORTH kept
        // only one line of height and clipped the wrapped button completely.
        JPanel action = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 3));
        action.setAlignmentX(Component.LEFT_ALIGNMENT);
        action.add(addFilterGroup);
        add(action);
    }

    void edit(DomainField field) {
        this.field = field;
        filterCondition.edit(field, null);
        addFilterGroup.setEnabled(creationEnabled && field != null);
    }

    void creationEnabled(boolean enabled) {
        creationEnabled = enabled;
        addFilterGroup.setEnabled(enabled && field != null);
    }

    /** Create a named filter group from the rule currently shown in this editor. */
    private void requestAddFilterGroup() {
        if (!creationEnabled) return;
        FilterCondition c = filterCondition.condition();
        if (c == null) {
            JOptionPane.showMessageDialog(this, "Select a field first.");
            return;
        }
        createFilterGroup(filterGroupName.get(), c);
    }

    private String askFilterGroupName() {
        JTextField name = new JTextField("Filter", 24);
        name.selectAll();
        int answer = JOptionPane.showConfirmDialog(this, name,
                "Name the new filter group", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        return answer == JOptionPane.OK_OPTION ? name.getText() : null;
    }

    /** Hosts normally use the standard name dialog; test hosts can supply a response. */
    void filterGroupNamePrompt(Supplier<String> prompt) {
        filterGroupName = prompt == null ? this::askFilterGroupName : prompt;
    }

    void createFilterGroup(String name, FilterCondition condition) {
        if (name == null || name.isBlank() || condition == null
                || filterGroupCreator == null) return;
        filterGroupCreator.accept(name.trim(), condition);
    }

}
