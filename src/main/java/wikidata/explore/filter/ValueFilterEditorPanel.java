package wikidata.explore.filter;

import aux.GridBagUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * UI editor for RuleNode numeric value filters.
 *
 * Intended rule-node use:
 *
 *   valueFilterEditor.setFilters(node.valueFilters());
 *
 *   node.valueFilters().clear();
 *   node.valueFilters().addAll(valueFilterEditor.filters());
 *
 * Property-browser hook:
 *
 *   valueFilterEditor.useProperty(pid, label);
 */
public class ValueFilterEditorPanel extends JPanel {

    private final DefaultListModel<WikidataValueFilter> model =
            new DefaultListModel<>();

    private final JList<WikidataValueFilter> list =
            new JList<>(model);

    private final JTextField fieldNameField =
            new JTextField(14);

    private final JTextField pidField =
            new JTextField(8);

    private final JTextField labelField =
            new JTextField(16);

    private final JComboBox<WikidataValueFilterOperator> operatorBox =
            new JComboBox<>(WikidataValueFilterOperator.values());

    private final JSpinner valueSpinner =
            new JSpinner(new SpinnerNumberModel(
                    0.0,
                    -1.0e12,
                    1.0e12,
                    1.0));

    private final JCheckBox requiredBox =
            new JCheckBox("required", true);

    private final JButton addButton =
            new JButton("Add");

    private final JButton replaceButton =
            new JButton("Replace");

    private final JButton removeButton =
            new JButton("Remove");

    private final JButton clearButton =
            new JButton("Clear");

    public ValueFilterEditorPanel() {
        super(new BorderLayout(6, 6));
        buildUi();
        wireActions();
    }

    public List<WikidataValueFilter> filters() {
        List<WikidataValueFilter> out =
                new ArrayList<>();

        for (int i = 0; i < model.size(); i++) {
            out.add(model.get(i));
        }

        return out;
    }

    public void setFilters(List<WikidataValueFilter> filters) {
        model.clear();

        if (filters != null) {
            for (WikidataValueFilter filter : filters) {
                if (filter != null) {
                    model.addElement(filter);
                }
            }
        }

        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    public void useProperty(String pid, String label) {
        pidField.setText(WikidataValueFilter.cleanPid(pid));
        labelField.setText(label == null ? "" : label);

        if (fieldNameField.getText().trim().isBlank()) {
            fieldNameField.setText(toFieldName(label, pid));
        }
    }

    private void buildUi() {
        setBorder(BorderFactory.createTitledBorder("Value filters"));

        JPanel editor =
                new JPanel(new GridBagLayout());

        int y = 0;

        editor.add(new JLabel("Field"),
                GridBagUtils.gbc(0, y, 0.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(3, 3, 3, 3)));

        editor.add(fieldNameField,
                GridBagUtils.gbc(1, y, 1.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(3, 3, 3, 3)));

        editor.add(new JLabel("PID"),
                GridBagUtils.gbc(2, y, 0.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(3, 3, 3, 3)));

        editor.add(pidField,
                GridBagUtils.gbc(3, y++, 0.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(3, 3, 3, 3)));

        editor.add(new JLabel("Label"),
                GridBagUtils.gbc(0, y, 0.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(3, 3, 3, 3)));

        editor.add(labelField,
                GridBagUtils.gbc(1, y, 1.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(3, 3, 3, 3)));

        editor.add(operatorBox,
                GridBagUtils.gbc(2, y, 0.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.NONE,
                        new Insets(3, 3, 3, 3)));

        editor.add(valueSpinner,
                GridBagUtils.gbc(3, y++, 0.0, 0.0,
                        GridBagConstraints.WEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(3, 3, 3, 3)));

        JPanel buttons =
                new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));

        buttons.add(requiredBox);
        buttons.add(addButton);
        buttons.add(replaceButton);
        buttons.add(removeButton);
        buttons.add(clearButton);

        editor.add(buttons,
                GridBagUtils.gbc(0, y, 4, 1.0, 0.0,
                        GridBagConstraints.EAST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(3, 3, 3, 3)));

        list.setVisibleRowCount(5);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        add(editor, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }

    private void wireActions() {
        addButton.addActionListener(e -> addFilter());
        replaceButton.addActionListener(e -> replaceSelected());
        removeButton.addActionListener(e -> removeSelected());
        clearButton.addActionListener(e -> model.clear());

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedIntoEditor();
            }
        });
    }

    private void addFilter() {
        WikidataValueFilter filter =
                readEditorFilter();

        if (filter == null) {
            return;
        }

        model.addElement(filter);
        list.setSelectedIndex(model.size() - 1);
    }

    private void replaceSelected() {
        int index =
                list.getSelectedIndex();

        if (index < 0) {
            addFilter();
            return;
        }

        WikidataValueFilter filter =
                readEditorFilter();

        if (filter == null) {
            return;
        }

        model.set(index, filter);
        list.setSelectedIndex(index);
    }

    private void removeSelected() {
        int index =
                list.getSelectedIndex();

        if (index >= 0) {
            model.remove(index);
        }
    }

    private WikidataValueFilter readEditorFilter() {
        String pid =
                WikidataValueFilter.cleanPid(pidField.getText());

        if (!pid.matches("P\\d+")) {
            JOptionPane.showMessageDialog(
                    this,
                    "Property PID must look like P1215.",
                    "Invalid property PID",
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }

        String field =
                fieldNameField.getText().trim();

        if (field.isBlank()) {
            field =
                    toFieldName(labelField.getText(), pid);
        }

        return new WikidataValueFilter(
                field,
                pid,
                labelField.getText().trim(),
                (WikidataValueFilterOperator) operatorBox.getSelectedItem(),
                ((Number) valueSpinner.getValue()).doubleValue(),
                requiredBox.isSelected());
    }

    private void loadSelectedIntoEditor() {
        WikidataValueFilter filter =
                list.getSelectedValue();

        if (filter == null) {
            return;
        }

        fieldNameField.setText(filter.fieldName());
        pidField.setText(filter.propertyPid());
        labelField.setText(filter.propertyLabel());
        operatorBox.setSelectedItem(filter.operator());
        valueSpinner.setValue(filter.numericValue());
        requiredBox.setSelected(filter.required());
    }

    private static String toFieldName(String label, String pid) {
        String s =
                label == null || label.isBlank() ? pid : label;

        s = s.trim()
             .replaceAll("[^A-Za-z0-9_]+", "_")
             .replaceAll("_+", "_");

        if (s.startsWith("_")) {
            s = s.substring(1);
        }

        if (s.endsWith("_")) {
            s = s.substring(0, s.length() - 1);
        }

        if (s.isBlank()) {
            s = "valueFilter";
        }

        if (Character.isDigit(s.charAt(0))) {
            s = "v_" + s;
        }

        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
