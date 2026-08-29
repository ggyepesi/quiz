package workbench;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Compact, reusable view of the typed workbench selections. */
public final class SelectionsButton extends JButton {
    private final WorkbenchSelections selections;
    private final List<MenuAction> actions = new ArrayList<>();
    private WorkbenchSelections.Registration registration;

    public SelectionsButton(WorkbenchSelections selections) {
        super("Reusable selections");
        this.selections = selections;
        setToolTipText("Show the entities and properties selected for reuse");
        addActionListener(event -> showMenu());
        refresh();
    }

    private void listen() {
        if (registration == null) registration = selections.onChange(this::refresh);
    }

    @Override public void addNotify() {
        super.addNotify();
        listen();
        refresh();
    }

    @Override public void removeNotify() {
        if (registration != null) {
            registration.close();
            registration = null;
        }
        super.removeNotify();
    }

    public SelectionsButton action(
            String label, BooleanSupplier enabled, Runnable operation) {
        if (label != null && !label.isBlank() && operation != null) {
            actions.add(new MenuAction(label,
                    enabled == null ? () -> true : enabled, operation));
        }
        return this;
    }

    private void refresh() {
        int count = selections.entities().size() + selections.properties().size();
        setText(count == 0
                ? "Reusable selections"
                : "Reusable selections (" + count + ")");
    }

    private static void header(JPopupMenu menu, String kind, int count) {
        JMenuItem item = new JMenuItem(count == 0 ? kind + ": none" : kind + " (" + count + ")");
        item.setEnabled(false);
        menu.add(item);
    }

    private void showMenu() {
        menu().show(this, 0, getHeight());
    }

    /** Builds the current menu so contextual actions evaluate against current UI state. */
    JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        // Every selected value is listed and individually removable: a collection the
        // reader cannot see the contents of is one they cannot correct.
        header(menu, "Entities", selections.entities().size());
        for (WorkbenchSelections.Entity value : selections.entities()) {
            JMenuItem item = new JMenuItem(
                    "  " + value.label() + " (" + value.qid() + ")  ✕");
            item.addActionListener(event -> selections.removeEntity(value));
            menu.add(item);
        }
        if (!selections.entities().isEmpty()) {
            JMenuItem clear = new JMenuItem("  Clear all entities");
            clear.addActionListener(event -> selections.clearEntity());
            menu.add(clear);
        }
        menu.addSeparator();
        header(menu, "Properties", selections.properties().size());
        for (WorkbenchSelections.Property value : selections.properties()) {
            JMenuItem item = new JMenuItem(
                    "  " + value.label() + " (" + value.pid() + ")  ✕");
            item.addActionListener(event -> selections.removeProperty(value));
            menu.add(item);
        }
        if (!selections.properties().isEmpty()) {
            JMenuItem clear = new JMenuItem("  Clear all properties");
            clear.addActionListener(event -> selections.clearProperty());
            menu.add(clear);
        }
        if (!actions.isEmpty()) {
            menu.addSeparator();
            for (MenuAction action : actions) {
                JMenuItem item = new JMenuItem(action.label());
                item.setEnabled(action.enabled().getAsBoolean());
                item.addActionListener(event -> action.operation().run());
                menu.add(item);
            }
        }
        return menu;
    }

    private record MenuAction(
            String label, BooleanSupplier enabled, Runnable operation) { }
}
