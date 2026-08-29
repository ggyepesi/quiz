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

    public SelectionsButton(WorkbenchSelections selections) {
        super("Reusable selections");
        this.selections = selections;
        setToolTipText("Show the entity and property selected for reuse");
        addActionListener(event -> showMenu());
        selections.onChange(this::refresh);
        refresh();
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
        int count = (selections.entity().isPresent() ? 1 : 0)
                + (selections.property().isPresent() ? 1 : 0);
        setText(count == 0
                ? "Reusable selections"
                : "Reusable selections (" + count + ")");
    }

    private void showMenu() {
        menu().show(this, 0, getHeight());
    }

    /** Builds the current menu so contextual actions evaluate against current UI state. */
    JPopupMenu menu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem entity = new JMenuItem(selections.entity()
                .map(value -> "Entity: " + value.label() + " (" + value.qid() + ")")
                .orElse("Entity: none"));
        entity.setEnabled(false);
        menu.add(entity);
        selections.entity().ifPresent(value -> {
            JMenuItem clear = new JMenuItem("Clear entity");
            clear.addActionListener(event -> selections.clearEntity());
            menu.add(clear);
        });
        menu.addSeparator();
        JMenuItem property = new JMenuItem(selections.property()
                .map(value -> "Property: " + value.label() + " (" + value.pid() + ")")
                .orElse("Property: none"));
        property.setEnabled(false);
        menu.add(property);
        selections.property().ifPresent(value -> {
            JMenuItem clear = new JMenuItem("Clear property");
            clear.addActionListener(event -> selections.clearProperty());
            menu.add(clear);
        });
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
