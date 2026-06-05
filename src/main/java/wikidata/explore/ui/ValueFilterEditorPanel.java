package wikidata.explore.ui;

import wikidata.explore.filter.WikidataValueFilter;
import wikidata.explore.filter.WikidataValueFilterOperator;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * UI editor for RuleNode numeric value filters.
 *
 * A numeric value filter restricts results to entities whose property
 * value satisfies a numeric comparison, e.g. apparent magnitude ≤ 6.0.
 *
 * Layout:
 *
 *   ┌─ Value filters ─────────────────────────────────────────────────┐
 *   │  [Field____] [PID___] [Label_______] [op▼] [value____] [✓req]  │
 *   ├─────────────────────────────────────┬──────────────────────────┤
 *   │  Configured filters:                │ [Add]                    │
 *   │   apparent magnitude ≤ 6.0         │ [Replace selected]       │
 *   │   ...                              │ [Remove selected]        │
 *   └─────────────────────────────────────┴──────────────────────────┘
 */
public class ValueFilterEditorPanel extends JPanel {

    private final DefaultListModel<WikidataValueFilter> model =
            new DefaultListModel<>();

    private final JList<WikidataValueFilter> list =
            new JList<>(model);

    // -- Editor row fields ------------------------------------------------
    private final JTextField fieldNameField = new JTextField(12);
    private final JTextField pidField       = new JTextField(7);
    private final JTextField labelField     = new JTextField(14);

    private final JComboBox<WikidataValueFilterOperator> operatorBox =
            new JComboBox<>(WikidataValueFilterOperator.values());

    private final JSpinner valueSpinner =
            new JSpinner(new SpinnerNumberModel(0.0, -1.0e12, 1.0e12, 1.0));

    private final JCheckBox requiredBox = new JCheckBox("required", true);

    // -- Action buttons ---------------------------------------------------
    private final JButton addButton     = new JButton("Add");
    private final JButton replaceButton = new JButton("Replace selected");
    private final JButton removeButton  = new JButton("Remove selected");
    private final JButton clearButton   = new JButton("Clear all");

    public ValueFilterEditorPanel() {
        super(new BorderLayout(6, 6));
        buildUi();
        wireActions();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public List<WikidataValueFilter> filters() {
        List<WikidataValueFilter> out = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) out.add(model.get(i));
        return out;
    }

    public void setFilters(List<WikidataValueFilter> filters) {
        model.clear();
        if (filters != null)
            for (WikidataValueFilter f : filters)
                if (f != null) model.addElement(f);
        if (!model.isEmpty()) list.setSelectedIndex(0);
    }

    /**
     * Pre-fills PID and label from the property browser.
     * Field name is auto-derived if blank.
     */
    public void useProperty(String pid, String label) {
        pidField.setText(WikidataValueFilter.cleanPid(pid));
        labelField.setText(label == null ? "" : label);
        if (fieldNameField.getText().trim().isBlank())
            fieldNameField.setText(toFieldName(label, pid));
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void buildUi() {
        setBorder(BorderFactory.createTitledBorder("Value filters"));

        // -- Editor row --------------------------------------------------
        JPanel editorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        editorRow.add(new JLabel("Field:"));
        editorRow.add(fieldNameField);
        editorRow.add(new JLabel("PID:"));
        editorRow.add(pidField);
        editorRow.add(new JLabel("Label:"));
        editorRow.add(labelField);
        editorRow.add(operatorBox);
        editorRow.add(valueSpinner);

        requiredBox.setToolTipText(
                "<html>When checked: entity must have this property value "
                + "and it must satisfy the comparison — entities without the "
                + "property are excluded.<br>"
                + "When unchecked: the filter is applied only if the property "
                + "exists (OPTIONAL + BOUND guard). Entities without the "
                + "property still appear.</html>");
        editorRow.add(requiredBox);

        // -- List with header + side buttons -----------------------------
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(4);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setColumnHeaderView(
                new JLabel("  Configured filters  (select to edit above)"));

        addButton.setToolTipText(
                "Add a new filter using the values entered above.");
        replaceButton.setToolTipText(
                "Overwrite the selected filter in the list with the values above.");
        removeButton.setToolTipText(
                "Remove the selected filter from the list.");
        clearButton.setToolTipText(
                "Remove all filters.");

        JPanel sideButtons = new JPanel(new GridLayout(0, 1, 0, 4));
        sideButtons.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        sideButtons.add(addButton);
        sideButtons.add(replaceButton);
        sideButtons.add(removeButton);
        sideButtons.add(clearButton);

        JPanel listArea = new JPanel(new BorderLayout(4, 0));
        listArea.add(listScroll, BorderLayout.CENTER);
        listArea.add(sideButtons, BorderLayout.EAST);

        add(editorRow, BorderLayout.NORTH);
        add(listArea,  BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // Event wiring
    // ------------------------------------------------------------------

    private void wireActions() {
        addButton.addActionListener(e -> addFilter());
        replaceButton.addActionListener(e -> replaceSelected());
        removeButton.addActionListener(e -> removeSelected());
        clearButton.addActionListener(e -> model.clear());

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelectedIntoEditor();
        });
    }

    private void addFilter() {
        WikidataValueFilter filter = readEditorFilter();
        if (filter == null) return;
        model.addElement(filter);
        list.setSelectedIndex(model.size() - 1);
    }

    private void replaceSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) { addFilter(); return; }
        WikidataValueFilter filter = readEditorFilter();
        if (filter == null) return;
        model.set(index, filter);
        list.setSelectedIndex(index);
    }

    private void removeSelected() {
        int index = list.getSelectedIndex();
        if (index >= 0) model.remove(index);
    }

    private WikidataValueFilter readEditorFilter() {
        String pid = WikidataValueFilter.cleanPid(pidField.getText());
        if (!pid.matches("P\\d+")) {
            JOptionPane.showMessageDialog(this,
                    "Property PID must look like P1215.",
                    "Invalid PID", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        String field = fieldNameField.getText().trim();
        if (field.isBlank()) field = toFieldName(labelField.getText(), pid);
        return new WikidataValueFilter(
                field, pid, labelField.getText().trim(),
                (WikidataValueFilterOperator) operatorBox.getSelectedItem(),
                ((Number) valueSpinner.getValue()).doubleValue(),
                requiredBox.isSelected());
    }

    private void loadSelectedIntoEditor() {
        WikidataValueFilter filter = list.getSelectedValue();
        if (filter == null) return;
        fieldNameField.setText(filter.fieldName());
        pidField.setText(filter.propertyPid());
        labelField.setText(filter.propertyLabel() == null ? "" : filter.propertyLabel());
        operatorBox.setSelectedItem(filter.operator());
        valueSpinner.setValue(filter.numericValue());
        requiredBox.setSelected(filter.required());
    }

    private static String toFieldName(String label, String pid) {
        String s = label == null || label.isBlank() ? pid : label;
        s = s.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        if (s.startsWith("_")) s = s.substring(1);
        if (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) s = "valueFilter";
        if (Character.isDigit(s.charAt(0))) s = "v_" + s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
