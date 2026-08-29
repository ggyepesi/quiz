package workbench;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Opens the shared reusable-selection collection with operations supplied by its caller. */
public final class SelectionsButton extends JButton {
    public enum Cardinality { SINGLE, MULTIPLE }

    private final WorkbenchSelections selections;
    private final List<AddAction> entityAdds = new ArrayList<>();
    private final List<AddAction> propertyAdds = new ArrayList<>();
    private final List<UseAction<WorkbenchSelections.Entity>> entityUses = new ArrayList<>();
    private final List<UseAction<WorkbenchSelections.Property>> propertyUses = new ArrayList<>();
    private WorkbenchSelections.Registration registration;

    public SelectionsButton(WorkbenchSelections selections) {
        super("Reusable selections");
        this.selections = selections;
        setToolTipText("Open the reusable entities and properties");
        addActionListener(event -> showDialog());
        refresh();
    }

    public SelectionsButton addEntities(String label, BooleanSupplier enabled, Runnable add) {
        entityAdds.add(new AddAction(label, enabled, add)); return this;
    }
    public SelectionsButton addProperties(String label, BooleanSupplier enabled, Runnable add) {
        propertyAdds.add(new AddAction(label, enabled, add)); return this;
    }
    public SelectionsButton useEntities(String label, Cardinality cardinality,
            Consumer<List<WorkbenchSelections.Entity>> use) {
        return useEntities(label, cardinality, () -> true, use);
    }
    public SelectionsButton useEntities(String label, Cardinality cardinality,
            BooleanSupplier enabled, Consumer<List<WorkbenchSelections.Entity>> use) {
        entityUses.add(oneCardinality(entityUses,
                new UseAction<>(label, cardinality, enabled, use), "entities"));
        return this;
    }
    public SelectionsButton useProperties(String label, Cardinality cardinality,
            Consumer<List<WorkbenchSelections.Property>> use) {
        return useProperties(label, cardinality, () -> true, use);
    }
    public SelectionsButton useProperties(String label, Cardinality cardinality,
            BooleanSupplier enabled, Consumer<List<WorkbenchSelections.Property>> use) {
        propertyUses.add(oneCardinality(propertyUses,
                new UseAction<>(label, cardinality, enabled, use), "properties"));
        return this;
    }

    @Override public void addNotify() {
        super.addNotify();
        if (registration == null) registration = selections.onChange(this::refresh);
        refresh();
    }
    @Override public void removeNotify() {
        if (registration != null) { registration.close(); registration = null; }
        super.removeNotify();
    }
    private void refresh() {
        int count = selections.entities().size() + selections.properties().size();
        setText(count == 0 ? "Reusable selections" : "Reusable selections (" + count + ")");
    }

    private void showDialog() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
                "Reusable selections", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content(dialog));
        dialog.setSize(600, 480);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** Builds this presenting context's view of the shared reusable collection. */
    public JComponent dialogContent() { return content(null); }

    private JComponent content(JDialog dialog) {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Entities", entityPanel(dialog));
        tabs.addTab("Properties", propertyPanel(dialog));
        return tabs;
    }

    private JComponent entityPanel(JDialog dialog) {
        DefaultListModel<WorkbenchSelections.Entity> model = model(selections.entities());
        JList<WorkbenchSelections.Entity> list = new JList<>(model);
        list.setCellRenderer(renderer(e -> e.label() + " (" + e.qid() + ")"));
        configureSelection(list, cardinality(entityUses));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        entityAdds.forEach(action -> actions.add(addButton(action,
                () -> replace(model, selections.entities()))));
        actions.add(removeButton("Remove selected entities", list, () -> {
            List.copyOf(list.getSelectedValuesList()).forEach(selections::removeEntity);
            replace(model, selections.entities());
        }));
        entityUses.forEach(use -> actions.add(useButton(use, list, dialog)));
        return listPanel(list, actions, "Collect entities in one tool and use them in another.");
    }

    private JComponent propertyPanel(JDialog dialog) {
        DefaultListModel<WorkbenchSelections.Property> model = model(selections.properties());
        JList<WorkbenchSelections.Property> list = new JList<>(model);
        list.setCellRenderer(renderer(p -> p.label() + " (" + p.pid() + ")"));
        configureSelection(list, cardinality(propertyUses));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        propertyAdds.forEach(action -> actions.add(addButton(action,
                () -> replace(model, selections.properties()))));
        actions.add(removeButton("Remove selected properties", list, () -> {
            List.copyOf(list.getSelectedValuesList()).forEach(selections::removeProperty);
            replace(model, selections.properties());
        }));
        propertyUses.forEach(use -> actions.add(useButton(use, list, dialog)));
        return listPanel(list, actions, "Properties are kept separately from entities.");
    }

    private static void configureSelection(JList<?> list, Cardinality cardinality) {
        list.setSelectionMode(cardinality == Cardinality.SINGLE
                ? ListSelectionModel.SINGLE_SELECTION
                : ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    }
    /**
     * A tab has ONE list, so its selection mode is one decision. Two use actions that
     * disagree about it are a wiring mistake, and it is caught where the wiring happens
     * — not when a reader eventually clicks the button and the dialog fails to build.
     */
    private static <T> UseAction<T> oneCardinality(
            List<UseAction<T>> existing, UseAction<T> action, String kind) {
        if (!existing.isEmpty() && existing.getFirst().cardinality() != action.cardinality()) {
            throw new IllegalArgumentException("The " + kind + " tab already uses "
                    + existing.getFirst().cardinality() + " selection; \"" + action.label
                    + "\" asks for " + action.cardinality() + ".");
        }
        return action;
    }

    /** Nothing to use means nothing constrains the list: several may be removed at once. */
    private static Cardinality cardinality(List<? extends UseAction<?>> actions) {
        return actions.isEmpty() ? Cardinality.MULTIPLE : actions.getFirst().cardinality();
    }
    private JButton addButton(AddAction action, Runnable after) {
        JButton button = new JButton(action.label);
        button.setEnabled(action.enabled == null || action.enabled.getAsBoolean());
        button.addActionListener(e -> { action.operation.run(); after.run(); });
        return button;
    }
    /**
     * Always offered. This dialog IS the collection's editor, so a reader who can see a
     * wrong value must be able to drop it — whatever the presenting tool happens to do
     * with the collection. It used to appear only where an ADD action was registered,
     * which left the vocabulary window able to show a mistake but not correct it.
     */
    private static <T> JButton removeButton(String text, JList<T> list, Runnable remove) {
        JButton button = new JButton(text); button.setEnabled(false);
        list.addListSelectionListener(e -> button.setEnabled(!list.isSelectionEmpty()));
        button.addActionListener(e -> remove.run()); return button;
    }
    private static <T> JButton useButton(UseAction<T> action, JList<T> list, JDialog dialog) {
        JButton button = new JButton(action.label);
        Runnable refresh = () -> button.setEnabled(
                (action.enabled == null || action.enabled.getAsBoolean())
                && (action.cardinality == Cardinality.SINGLE
                ? list.getSelectedIndices().length == 1 : !list.isSelectionEmpty()));
        list.addListSelectionListener(e -> refresh.run());
        button.addActionListener(e -> {
            action.operation.accept(List.copyOf(list.getSelectedValuesList()));
            if (dialog != null) dialog.dispose();
        });
        refresh.run(); return button;
    }
    private static JPanel listPanel(JList<?> list, JPanel actions, String hint) {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel(hint), BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH); return panel;
    }
    private static <T> ListCellRenderer<T> renderer(java.util.function.Function<T, String> text) {
        return (list, value, index, selected, focus) -> {
            JLabel label = new JLabel(text.apply(value)); label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        };
    }
    private static <T> DefaultListModel<T> model(List<T> values) {
        DefaultListModel<T> model = new DefaultListModel<>(); values.forEach(model::addElement); return model;
    }
    private static <T> void replace(DefaultListModel<T> model, List<T> values) {
        model.clear(); values.forEach(model::addElement);
    }

    private record AddAction(String label, BooleanSupplier enabled, Runnable operation) { }
    private record UseAction<T>(String label, Cardinality cardinality,
                                BooleanSupplier enabled, Consumer<List<T>> operation) { }
}
