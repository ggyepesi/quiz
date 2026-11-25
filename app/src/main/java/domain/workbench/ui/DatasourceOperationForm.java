package domain.workbench.ui;

import domain.datasource.api.BindingScope;
import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceProvider;
import domain.datasource.api.DatasourceRegistry;
import domain.datasource.api.ParameterDescriptor;
import domain.datasource.api.SourceRecipe;

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
    private final JPanel advancedDetail = new JPanel(new BorderLayout(4, 4));
    private final JPanel advancedParameters = new JPanel(new GridBagLayout());
    private final JLabel advancedHelp = new JLabel(" ");
    private final Map<String, JComponent> inputs = new LinkedHashMap<>();
    private Map<String, String> storedParameters = Map.of();
    private boolean editingSavedRecipe;
    private boolean separateAdvanced;
    private Runnable changed = () -> { };

    /**
     * @param offered which field-value operations this host may bind; the rest of the
     *                registry is not shown
     */
    public DatasourceOperationForm(DatasourceRegistry registry,
            Predicate<SourceRecipe> offered) {
        this(registry, BindingScope.FIELD_VALUE, offered);
    }

    /**
     * Builds the same provider-declared form for one binding scope. The hosting editor
     * decides the model site; this component only shows operations valid at that site.
     */
    public DatasourceOperationForm(
            DatasourceRegistry registry,
            BindingScope scope,
            Predicate<SourceRecipe> offered) {
        super(new BorderLayout(4, 4));
        operations.setName("domain.datasource.operation");
        BindingScope requested = scope == null ? BindingScope.FIELD_VALUE : scope;
        Predicate<SourceRecipe> filter = offered == null ? recipe -> true : offered;
        for (DatasourceProvider provider : registry.providers()) {
            for (DatasourceOperation operation : provider.operations()) {
                if (operation.scope() != requested) continue;
                if (requested == BindingScope.FIELD_VALUE
                        && !operation.outputSchema().kind().bindableToField()) continue;
                if (!filter.test(new SourceRecipe(provider.id(), operation.id(), Map.of())))
                    continue;
                operations.addItem(new Choice(provider, operation));
            }
        }
        operations.setSelectedIndex(-1);
        operations.addActionListener(event -> {
            showParameters(Map.of(), false);
            changed.run();
        });
        add(operations, BorderLayout.NORTH);
        add(parameters, BorderLayout.CENTER);
        add(help, BorderLayout.SOUTH);
        advancedDetail.add(advancedParameters, BorderLayout.CENTER);
        advancedDetail.add(advancedHelp, BorderLayout.SOUTH);
    }

    public void onChange(Runnable changed) {
        this.changed = changed == null ? () -> { } : changed;
    }

    public boolean hasOperations() { return operations.getItemCount() > 0; }

    /** The operations this form offers, in registry order. A host that lets the reader
     * choose between these and its own non-datasource configuration lists them in its
     * one chooser instead of building a second list. */
    public List<Choice> choices() {
        List<Choice> result = new ArrayList<>();
        for (int i = 0; i < operations.getItemCount(); i++) result.add(operations.getItemAt(i));
        return List.copyOf(result);
    }

    /** Hides this form's own operation list when the host's chooser already decides it. */
    public void operationChooserVisible(boolean visible) {
        operations.setVisible(visible);
    }

    /** Hosts the same inputs in a second detail card of the configuration tree. */
    public JComponent advancedDetail() { return advancedDetail; }

    public void showAdvancedSeparately() { separateAdvanced = true; }

    /** Supplies a draft to the Advanced projection only for operations with advanced inputs. */
    public SourceRecipe recipeWithAdvancedSettings() {
        Choice choice = (Choice) operations.getSelectedItem();
        return choice != null && choice.operation().hasAdvancedSettings()
                ? recipe() : null;
    }

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
        showParameters(match == null ? Map.of() : recipe.parameters(), match != null);
    }

    /** The edited recipe, or null when no operation is chosen. */
    public SourceRecipe recipe() {
        Choice choice = (Choice) operations.getSelectedItem();
        if (choice == null) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (ParameterDescriptor parameter : choice.operation().parameters()) {
            String key = parameter.key();
            String edited = value(inputs.get(key));
            String stored = storedParameters.get(key);
            if (editingSavedRecipe && parameter.advanced()
                    && parameter.usesDefault(edited) && parameter.usesDefault(stored)) {
                if (stored != null) values.put(key, stored);
            } else {
                values.put(key, edited);
            }
        }
        return new SourceRecipe(choice.provider().id(), choice.operation().id(), values);
    }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        operations.setEnabled(enabled);
        inputs.values().forEach(input -> input.setEnabled(enabled));
    }

    private void showParameters(Map<String, String> stored, boolean editingSavedRecipe) {
        storedParameters = stored;
        this.editingSavedRecipe = editingSavedRecipe;
        parameters.removeAll();
        advancedParameters.removeAll();
        inputs.clear();
        Choice choice = (Choice) operations.getSelectedItem();
        List<String> helpLines = new ArrayList<>();
        List<String> advancedHelpLines = new ArrayList<>();
        if (choice != null) {
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(2, 0, 2, 6);
            for (ParameterDescriptor parameter : choice.operation().parameters()) {
                boolean advanced = separateAdvanced && parameter.advanced();
                JPanel target = advanced ? advancedParameters : parameters;
                String value = parameter.effectiveValue(stored.get(parameter.key()));
                JComponent input = input(parameter, value, choice.provider().id());
                input.setName("domain.datasource.parameter." + parameter.key());
                inputs.put(parameter.key(), input);
                c.gridy = target.getComponentCount() / 2;
                c.gridx = 0;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                target.add(new JLabel(parameter.label()
                        + (parameter.required() ? " *:" : ":")), c);
                c.gridx = 1;
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                target.add(input, c);
                if (advanced && !parameter.defaultValue().isBlank()) {
                    advancedHelpLines.add(parameter.label() + " defaults to " + parameter.defaultValue() + ".");
                }
                if (!parameter.help().isBlank()) {
                    (advanced ? advancedHelpLines : helpLines).add(parameter.label() + ": " + parameter.help());
                }
            }
        }
        help.setText(helpText(helpLines));
        advancedHelp.setText(helpText(advancedHelpLines));
        parameters.revalidate();
        parameters.repaint();
        advancedParameters.revalidate();
        advancedParameters.repaint();
    }

    private static String helpText(List<String> lines) {
        return lines.isEmpty() ? " " : "<html>" + String.join("<br>", lines.stream()
                .map(DatasourceOperationForm::escape).toList()) + "</html>";
    }

    private JComponent input(
            ParameterDescriptor parameter, String value, String providerId) {
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
            case RELATION_ROUTES -> new RelationRoutesInput(
                    providerId, value, changed);
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
        if (input instanceof RelationRoutesInput routes) return routes.value();
        return "";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
