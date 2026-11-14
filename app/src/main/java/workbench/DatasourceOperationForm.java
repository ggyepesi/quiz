package workbench;

import datasource.api.BindingScope;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceProvider;
import datasource.api.DatasourceRegistry;
import datasource.api.ParameterDescriptor;
import datasource.api.SourceRecipe;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Chooses one registered datasource operation and edits its declared parameters.
 *
 * <p>The operation list and every input come from the registry: a provider declares its
 * operations and their {@link ParameterDescriptor}s, and this form renders them. Adding a
 * provider therefore adds a choice here without touching the form or the editors that
 * host it.
 */
public final class DatasourceOperationForm extends JPanel {

    /** One offered operation, shown as "provider — operation". */
    public record Choice(DatasourceProvider provider, DatasourceOperation operation) {
        @Override public String toString() {
            return provider.displayName() + " — " + operation.displayName();
        }
    }

    private final JComboBox<Choice> operations = new JComboBox<>();
    private final JPanel parameters = new JPanel(new GridBagLayout());
    private final JLabel help = new JLabel(" ");
    private final Map<String, JComponent> inputs = new LinkedHashMap<>();
    private Runnable changed = () -> { };

    /**
     * @param offered which field-value operations this host may bind; the rest of the
     *                registry is not shown
     */
    public DatasourceOperationForm(DatasourceRegistry registry,
            Predicate<SourceRecipe> offered) {
        super(new BorderLayout(4, 4));
        for (DatasourceProvider provider : registry.providers()) {
            for (DatasourceOperation operation : provider.operations()) {
                if (operation.scope() != BindingScope.FIELD_VALUE
                        || !operation.outputSchema().kind().bindableToField()) continue;
                if (!offered.test(new SourceRecipe(provider.id(), operation.id(), Map.of())))
                    continue;
                operations.addItem(new Choice(provider, operation));
            }
        }
        operations.setSelectedIndex(-1);
        operations.addActionListener(event -> {
            showParameters(Map.of());
            changed.run();
        });
        add(operations, BorderLayout.NORTH);
        add(parameters, BorderLayout.CENTER);
        add(help, BorderLayout.SOUTH);
    }

    public void onChange(Runnable changed) {
        this.changed = changed == null ? () -> { } : changed;
    }

    public boolean hasOperations() { return operations.getItemCount() > 0; }

    /** Shows {@code recipe}, or nothing chosen when it is null or not offered here. */
    public void show(SourceRecipe recipe) {
        Choice match = null;
        for (int i = 0; recipe != null && i < operations.getItemCount(); i++) {
            Choice choice = operations.getItemAt(i);
            if (choice.provider().id().equals(recipe.providerId())
                    && choice.operation().id().equals(recipe.operationId())) {
                match = choice;
            }
        }
        java.awt.event.ActionListener[] listeners = operations.getActionListeners();
        for (java.awt.event.ActionListener listener : listeners) {
            operations.removeActionListener(listener);
        }
        operations.setSelectedItem(match);
        if (match == null) operations.setSelectedIndex(-1);
        for (java.awt.event.ActionListener listener : listeners) {
            operations.addActionListener(listener);
        }
        showParameters(match == null ? Map.of() : recipe.parameters());
    }

    /** The edited recipe, or null when no operation is chosen. */
    public SourceRecipe recipe() {
        Choice choice = (Choice) operations.getSelectedItem();
        if (choice == null) return null;
        Map<String, String> values = new LinkedHashMap<>();
        inputs.forEach((key, input) -> values.put(key, value(input)));
        return new SourceRecipe(choice.provider().id(), choice.operation().id(), values);
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        operations.setEnabled(enabled);
        inputs.values().forEach(input -> input.setEnabled(enabled));
    }

    private void showParameters(Map<String, String> stored) {
        parameters.removeAll();
        inputs.clear();
        Choice choice = (Choice) operations.getSelectedItem();
        List<String> helpLines = new ArrayList<>();
        if (choice != null) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = 0;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(2, 0, 2, 6);
            for (ParameterDescriptor parameter : choice.operation().parameters()) {
                String value = stored.getOrDefault(parameter.key(), "");
                if (value.isBlank()) value = parameter.defaultValue();
                JComponent input = input(parameter, value);
                inputs.put(parameter.key(), input);
                c.gridx = 0;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                parameters.add(new JLabel(parameter.label()
                        + (parameter.required() ? " *:" : ":")), c);
                c.gridx = 1;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                parameters.add(input, c);
                c.gridy++;
                if (!parameter.help().isBlank()) {
                    helpLines.add(parameter.label() + ": " + parameter.help());
                }
            }
        }
        help.setText(helpLines.isEmpty() ? " "
                : "<html>" + String.join("<br>", helpLines.stream()
                        .map(DatasourceOperationForm::escape).toList()) + "</html>");
        parameters.revalidate();
        parameters.repaint();
    }

    private JComponent input(ParameterDescriptor parameter, String value) {
        return switch (parameter.kind()) {
            case BOOLEAN -> {
                JCheckBox box = new JCheckBox();
                box.setSelected(Boolean.parseBoolean(value));
                box.addActionListener(event -> changed.run());
                yield box;
            }
            case CHOICE -> {
                JComboBox<String> box = new JComboBox<>(
                        parameter.options().toArray(String[]::new));
                box.setSelectedItem(value.isBlank() ? null : value);
                box.addActionListener(event -> changed.run());
                yield box;
            }
            default -> {
                JTextField field = new JTextField(value, 16);
                field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                        changed.run();
                    }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                        changed.run();
                    }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                        changed.run();
                    }
                });
                yield field;
            }
        };
    }

    private static String value(JComponent input) {
        if (input instanceof JCheckBox box) return String.valueOf(box.isSelected());
        if (input instanceof JComboBox<?> box) {
            Object selected = box.getSelectedItem();
            return selected == null ? "" : selected.toString();
        }
        if (input instanceof JTextField field) return field.getText().trim();
        return "";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
