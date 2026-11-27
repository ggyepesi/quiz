package domain.transform.pipeline.ui;

import domain.DomainField;
import domain.DomainModel;
import domain.transform.FilterCondition;
import domain.transform.FilterOperator;
import domain.transform.ui.TransformController;
import objectview.field.FieldKind;
import objectview.field.FieldPath;
import objectview.utils.swing.WrapLayout;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/** The operator and value controls for a filter group or saved transformation step. */
final class FilterConditionEditor extends JPanel {
    private final JComboBox<FilterOperator> operator = new JComboBox<>();
    private final JComboBox<String> value = new JComboBox<>();
    private final JTextField value2 = new JTextField(10);
    private final BiFunction<String, FieldPath, DomainModel.ValueSelection> values;
    private final BiFunction<String, FieldPath, Object> sample;
    private DomainField field;
    private Object originalValue;
    private Object originalValue2;

    FilterConditionEditor(BiFunction<String, FieldPath, DomainModel.ValueSelection> values,
            BiFunction<String, FieldPath, Object> sample) {
        super(new WrapLayout(FlowLayout.LEFT, 6, 3));
        this.values = values;
        this.sample = sample;
        operator.setName("filter.operator");
        value.setName("filter.value");
        value.setPrototypeDisplayValue("wwwwwwww");
        add(new JLabel("Operator:"));
        add(operator);
        add(new JLabel("Value:"));
        add(value);
        add(new JLabel("and:"));
        add(value2);
        operator.addActionListener(event -> updateValueControls());
        edit(null, null);
    }

    void edit(DomainField field, FilterCondition existing) {
        this.field = field;
        originalValue = existing == null ? null : existing.value();
        originalValue2 = existing == null ? null : existing.value2();
        var previous = existing == null ? selectedOperator() : existing.operator();
        operator.removeAllItems();
        FieldKind kind = kindOf(field);
        for (FilterOperator operation : FilterOperator.values()) {
            if (operation.appliesTo(kind)) operator.addItem(operation);
        }
        if (previous != null && previous.appliesTo(kind)) operator.setSelectedItem(previous);
        var choices = field == null ? new DomainModel.ValueSelection(List.of(), false)
                : values.apply(field.type(), field.fieldPath());
        value.removeAllItems();
        choices.values().forEach(value::addItem);
        value.setEditable(!choices.exhaustive());
        if (existing != null && originalValue != null) {
            String saved = originalValue.toString();
            if (choices.exhaustive() && !choices.values().contains(saved)) value.addItem(saved);
            value.setSelectedItem(saved);
        } else if (!choices.exhaustive()) {
            value.setSelectedItem("");
        }
        value2.setText(Objects.toString(originalValue2, ""));
        updateValueControls();
    }

    FilterCondition condition() {
        var selected = selectedOperator();
        if (field == null || selected == null) return null;
        Object item = value.isEditable() ? value.getEditor().getItem() : value.getSelectedItem();
        Object first = selected.isUnary() ? null : unchangedOrParse(item, originalValue);
        Object second = selected.isBinary()
                ? unchangedOrParse(value2.getText(), originalValue2) : null;
        return new FilterCondition(field, selected, first, second);
    }

    private FilterOperator selectedOperator() {
        return (FilterOperator) operator.getSelectedItem();
    }

    private void updateValueControls() {
        var selected = selectedOperator();
        value.setEnabled(selected != null && !selected.isUnary());
        value2.setEnabled(selected != null && selected.isBinary());
    }

    private FieldKind kindOf(DomainField field) {
        if (field == null) return FieldKind.UNKNOWN;
        if (field.collection()) return FieldKind.COLLECTION;
        if (field.reference()) return FieldKind.REFERENCE;
        if (field.kind() != null && field.kind() != FieldKind.UNKNOWN) return field.kind();
        return FieldKind.ofValue(sample.apply(field.type(), field.fieldPath()));
    }

    private static Object unchangedOrParse(Object item, Object original) {
        String text = Objects.toString(item, "");
        return text.equals(Objects.toString(original, ""))
                ? original : TransformController.parseValue(text);
    }
}
