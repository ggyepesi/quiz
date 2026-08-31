package wikidata.explore.workbench;

import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.ModelModuleChangePlan;
import wikidata.explore.model.ModelModuleImport;
import wikidata.explore.model.ModelModuleStore;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Explicit add/update/remove controls for exact shared-module pins. */
final class SharedModulesPanel extends JPanel {
    private final GeneratedProjectModel model;
    private final ModelModuleStore modules;
    private final DefaultListModel<ModelModuleImport> rows = new DefaultListModel<>();
    private final JList<ModelModuleImport> list = new JList<>(rows);
    private final JTextArea details = new JTextArea();
    private Runnable afterChange = () -> { };

    SharedModulesPanel(GeneratedProjectModel model, ModelModuleStore modules) {
        super(new BorderLayout(8, 8));
        this.model = model;
        this.modules = modules;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel title = new JLabel("Shared modules");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        add(title, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source,
                    Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(source, value, index, selected, focus);
                if (value instanceof ModelModuleImport pin) setText(pin.coordinate());
                return this;
            }
        });
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(list), new JScrollPane(details));
        split.setResizeWeight(.55);
        add(split, BorderLayout.CENTER);

        JButton add = new JButton("Add module…");
        JButton update = new JButton("Update…");
        JButton remove = new JButton("Remove…");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        actions.add(add);
        actions.add(update);
        actions.add(remove);
        add(actions, BorderLayout.SOUTH);
        add.addActionListener(e -> addModule());
        update.addActionListener(e -> updateModule());
        remove.addActionListener(e -> removeModule());
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showDetails(); });
        refresh();
    }

    void afterChange(Runnable action) { afterChange = action == null ? () -> { } : action; }

    void refresh() {
        String selected = list.getSelectedValue() == null ? ""
                : list.getSelectedValue().coordinate();
        rows.clear();
        model.imports().stream().map(ModelModuleImport::copy).forEach(rows::addElement);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).coordinate().equals(selected)) list.setSelectedIndex(i);
        }
        if (list.getSelectedIndex() < 0 && !rows.isEmpty()) list.setSelectedIndex(0);
        showDetails();
    }

    private void showDetails() {
        ModelModuleImport pin = list.getSelectedValue();
        details.setText(pin == null
                ? "No shared module is imported. Add one to reuse its classes without copying them."
                : pin.coordinate() + "\nDigest: " + pin.contentDigest() + "\n\nDeclarations:\n"
                + String.join("\n", pin.declarationIds()));
        details.setCaretPosition(0);
    }

    private void addModule() {
        try {
            List<ModelModuleImport> choices = modules.available().stream()
                    .filter(pin -> model.imports().stream().noneMatch(existing ->
                            existing.moduleId().equals(pin.moduleId())))
                    .toList();
            ModelModuleImport chosen = choose("Add shared module", choices);
            if (chosen != null) confirm(ModelModuleChangePlan.add(model, modules, chosen));
        } catch (Exception failure) { showFailure("Could not add module", failure); }
    }

    private void updateModule() {
        ModelModuleImport current = list.getSelectedValue();
        if (current == null) return;
        try {
            List<ModelModuleImport> choices = modules.available().stream()
                    .filter(pin -> pin.moduleId().equals(current.moduleId()))
                    .filter(pin -> !pin.contentDigest().equals(current.contentDigest()))
                    .toList();
            ModelModuleImport chosen = choose("Update " + current.coordinate(), choices);
            if (chosen != null) confirm(ModelModuleChangePlan.update(
                    model, modules, current, chosen));
        } catch (Exception failure) { showFailure("Could not update module", failure); }
    }

    private void removeModule() {
        ModelModuleImport current = list.getSelectedValue();
        if (current == null) return;
        try {
            confirm(ModelModuleChangePlan.remove(model, modules, current));
        } catch (Exception failure) { showFailure("Could not remove module", failure); }
    }

    private ModelModuleImport choose(String title, List<ModelModuleImport> choices) {
        if (choices.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No applicable module version is available.",
                    title, JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        Object[] labels = choices.stream().map(ModelModuleImport::coordinate).toArray();
        String selected = (String) JOptionPane.showInputDialog(this, "Version:", title,
                JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (selected == null) return null;
        return choices.stream().filter(pin -> pin.coordinate().equals(selected))
                .findFirst().orElse(null);
    }

    private void confirm(ModelModuleChangePlan plan) {
        JTextArea preview = new JTextArea(plan.summary() + "\n\n"
                + String.join("\n", plan.impact()), 10, 54);
        preview.setEditable(false);
        preview.setLineWrap(true);
        preview.setWrapStyleWord(true);
        int answer = JOptionPane.showConfirmDialog(this, new JScrollPane(preview),
                plan.summary(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        plan.apply();
        refresh();
        afterChange.run();
    }

    private void showFailure(String title, Exception failure) {
        JOptionPane.showMessageDialog(this,
                failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                title, JOptionPane.ERROR_MESSAGE);
    }
}
