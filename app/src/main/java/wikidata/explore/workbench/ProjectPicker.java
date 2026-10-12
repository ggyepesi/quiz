package wikidata.explore.workbench;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The two ways to open a saved project, with one unambiguous open-project selection.
 *
 * <p>Domain and Model used to be two bare combo boxes on the same crowded row.  Both
 * retained a selection, although only one project can be open, so after opening History
 * the header could still appear to say Periodic Table.  The row could also clip the Model
 * box down to an ellipsis.  This component owns both facts: the open project is selected
 * only in the picker for its kind, while the other picker remains an explicit open action.
 */
final class ProjectPicker extends JPanel {

    private static final String DOMAIN_PROMPT = "Open a domain…";
    private static final String MODEL_PROMPT = "Open a model…";

    private final JComboBox<String> domains = new JComboBox<>();
    private final JComboBox<String> models = new JComboBox<>();
    private Consumer<String> onOpen = ignored -> { };
    private String openProject = "";
    private boolean refreshing;

    ProjectPicker() {
        super(new GridLayout(0, 1, 0, 0));
        configure(domains, DOMAIN_PROMPT,
                "Saved domains. Selecting one opens it.");
        configure(models, MODEL_PROMPT,
                "Saved models. Selecting one opens it.");
        add(row("Domain:", domains));
        add(row("Model:", models));

        domains.addActionListener(event -> openSelected(domains));
        models.addActionListener(event -> openSelected(models));
    }

    void onOpen(Consumer<String> action) {
        onOpen = action == null ? ignored -> { } : action;
    }

    void showProjects(
            Collection<String> domainNames,
            Collection<String> modelNames,
            String currentProject,
            boolean currentIsModel) {
        refreshing = true;
        try {
            openProject = Objects.requireNonNullElse(currentProject, "");
            replace(domains, domainNames);
            replace(models, modelNames);
            domains.setSelectedItem(currentIsModel ? null : openProject);
            models.setSelectedItem(currentIsModel ? openProject : null);
        } finally {
            refreshing = false;
        }
        revalidate();
        repaint();
    }

    void setEditingEnabled(boolean enabled) {
        domains.setEnabled(enabled);
        models.setEnabled(enabled);
    }

    JComboBox<String> domainSelector() {
        return domains;
    }

    JComboBox<String> modelSelector() {
        return models;
    }

    private void openSelected(JComboBox<String> selector) {
        if (refreshing) return;
        Object selected = selector.getSelectedItem();
        if (selected != null && !selected.toString().equals(openProject)) {
            String requestedProject = selected.toString();
            // Aqua is still painting its selection blink while this listener runs.
            // Rebuilding either combo here makes Aqua repaint a list whose graphics
            // has already gone away (and can leave its first item displayed). Finish
            // the gesture first; the next event owns the project replacement.
            SwingUtilities.invokeLater(() -> onOpen.accept(requestedProject));
        }
    }

    private static void replace(
            JComboBox<String> selector,
            Collection<String> names) {
        selector.removeAllItems();
        if (names != null) names.forEach(selector::addItem);
    }

    private static JPanel row(String label, JComboBox<String> selector) {
        JPanel row = new JPanel(new GridBagLayout());
        GridBagConstraints text = new GridBagConstraints();
        text.gridx = 0;
        text.insets = new Insets(2, 4, 2, 4);
        text.anchor = GridBagConstraints.WEST;
        row.add(new JLabel(label), text);

        GridBagConstraints choice = new GridBagConstraints();
        choice.gridx = 1;
        choice.weightx = 1;
        choice.fill = GridBagConstraints.HORIZONTAL;
        choice.insets = new Insets(2, 0, 2, 4);
        row.add(selector, choice);
        return row;
    }

    private static void configure(
            JComboBox<String> selector,
            String prompt,
            String tooltip) {
        selector.setToolTipText(tooltip);
        selector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean selected,
                    boolean focus) {
                super.getListCellRendererComponent(
                        list, value, index, selected, focus);
                setText(value == null ? prompt : value.toString());
                setToolTipText(value == null ? tooltip : value.toString());
                return this;
            }
        });
    }
}
