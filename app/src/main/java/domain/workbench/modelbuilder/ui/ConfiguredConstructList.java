package domain.workbench.modelbuilder.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A visible list of configured child constructs and an explicit way to add one.
 *
 * <p>The combo chooses only what the <em>Add</em> command will create. It never stands
 * in for the configured children. Selecting a configured row is inspection and chooses
 * what the detail editor and Remove command act on; it never changes the list.</p>
 */
final class ConfiguredConstructList<T, K> extends JPanel {
    private final DefaultListModel<T> model = new DefaultListModel<>();
    private final JList<T> configured = new JList<>(model);
    private final JComboBox<K> kind = new JComboBox<>();
    private final JButton add = new JButton("Add");
    private final JButton remove = new JButton("Remove");
    private final JButton up = new JButton("Up");
    private final JButton down = new JButton("Down");
    private Function<K, T> factory = ignored -> null;
    private Consumer<T> beforeSelectionChanges = ignored -> { };
    private Consumer<T> selectionChanged = ignored -> { };
    private boolean addingEnabled = true;
    private boolean updating;

    ConfiguredConstructList(String title) {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder(title));
        configured.setName("configured.constructs");
        kind.setName("configured.construct.kind");
        add.setName("configured.construct.add");
        remove.setName("configured.construct.remove");
        up.setName("configured.construct.up");
        down.setName("configured.construct.down");
        configured.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        configured.setVisibleRowCount(4);
        configured.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || updating) return;
            selectionChanged.accept(configured.getSelectedValue());
            refreshButtons();
        });
        kind.addActionListener(event -> refreshButtons());
        add.addActionListener(event -> addSelectedKind());
        remove.addActionListener(event -> removeSelected());
        up.addActionListener(event -> moveSelected(-1));
        down.addActionListener(event -> moveSelected(1));

        JPanel commands = new JPanel();
        commands.setLayout(new BoxLayout(commands, BoxLayout.Y_AXIS));
        commands.add(new JLabel("Add:"));
        commands.add(kind);
        commands.add(Box.createVerticalStrut(4));
        commands.add(add);
        commands.add(remove);
        commands.add(up);
        commands.add(down);
        add(new JScrollPane(configured), BorderLayout.CENTER);
        add(commands, BorderLayout.EAST);
        refreshButtons();
    }

    void kinds(List<K> values) {
        kind.removeAllItems();
        kind.addItem(null);
        if (values != null) values.forEach(kind::addItem);
        kind.setSelectedItem(null);
        refreshButtons();
    }

    void factory(Function<K, T> value) {
        factory = value == null ? ignored -> null : value;
    }

    void beforeSelectionChanges(Consumer<T> value) {
        beforeSelectionChanges = value == null ? ignored -> { } : value;
    }

    void selectionChanged(Consumer<T> value) {
        selectionChanged = value == null ? ignored -> { } : value;
    }

    void describe(Function<T, String> value) {
        Function<T, String> text = value == null ? String::valueOf : value;
        configured.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object item, int index,
                    boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, item, index, selected, focus);
                @SuppressWarnings("unchecked") T typed = (T) item;
                label.setText(item == null ? " " : text.apply(typed));
                return label;
            }
        });
    }

    void show(List<T> values) {
        updating = true;
        try {
            model.clear();
            if (values != null) values.forEach(model::addElement);
            if (!model.isEmpty()) configured.setSelectedIndex(0);
        } finally {
            updating = false;
        }
        selectionChanged.accept(configured.getSelectedValue());
        refreshButtons();
    }

    List<T> values() {
        List<T> result = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) result.add(model.get(i));
        return List.copyOf(result);
    }

    T selected() { return configured.getSelectedValue(); }

    /** Availability of the explicit Add command. Existing children remain inspectable
     *  and removable when the current owner shape cannot accept another child. */
    void allowAdding(boolean value) {
        addingEnabled = value;
        kind.setEnabled(value);
        refreshButtons();
    }

    void add(K value) {
        kind.setSelectedItem(value);
        addSelectedKind();
    }

    void replaceSelected(T value) {
        int index = configured.getSelectedIndex();
        if (index < 0 || value == null) return;
        updating = true;
        try {
            model.set(index, value);
            configured.setSelectedIndex(index);
        } finally {
            updating = false;
        }
    }

    private void addSelectedKind() {
        @SuppressWarnings("unchecked") K selectedKind = (K) kind.getSelectedItem();
        if (selectedKind == null) return;
        beforeSelectionChanges.accept(configured.getSelectedValue());
        T value = factory.apply(selectedKind);
        if (value == null) return;
        model.addElement(value);
        configured.setSelectedIndex(model.size() - 1);
        kind.setSelectedItem(null);
    }

    private void removeSelected() {
        int index = configured.getSelectedIndex();
        if (index < 0) return;
        beforeSelectionChanges.accept(configured.getSelectedValue());
        model.remove(index);
        if (!model.isEmpty()) configured.setSelectedIndex(Math.min(index, model.size() - 1));
        else selectionChanged.accept(null);
        refreshButtons();
    }

    /** The children are an ordered list: their order is authored (written back with the
     *  rest of the list on Apply), and a child's place in it is how the action tree
     *  identifies it. Moving keeps the same child selected. */
    private void moveSelected(int by) {
        int index = configured.getSelectedIndex();
        int target = index + by;
        if (index < 0 || target < 0 || target >= model.size()) return;
        beforeSelectionChanges.accept(configured.getSelectedValue());
        updating = true;
        try {
            T moved = model.remove(index);
            model.add(target, moved);
            configured.setSelectedIndex(target);
        } finally {
            updating = false;
        }
        refreshButtons();
    }

    private void refreshButtons() {
        int index = configured.getSelectedIndex();
        add.setEnabled(addingEnabled && kind.getSelectedItem() != null);
        remove.setEnabled(index >= 0);
        up.setEnabled(index > 0);
        down.setEnabled(index >= 0 && index < model.size() - 1);
    }
}
