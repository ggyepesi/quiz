package workbench;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/** Compact, reusable view of the typed workbench selections. */
public final class SelectionsButton extends JButton {
    private final WorkbenchSelections selections;

    public SelectionsButton(WorkbenchSelections selections) {
        super("Selections");
        this.selections = selections;
        setToolTipText("Show the entity and property selected for reuse");
        addActionListener(event -> showMenu());
        selections.onChange(this::refresh);
        refresh();
    }

    private void refresh() {
        int count = (selections.entity().isPresent() ? 1 : 0)
                + (selections.property().isPresent() ? 1 : 0);
        setText(count == 0 ? "Selections" : "Selections (" + count + ")");
    }

    private void showMenu() {
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
        menu.show(this, 0, getHeight());
    }
}
