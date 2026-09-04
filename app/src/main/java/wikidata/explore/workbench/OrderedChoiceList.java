package wikidata.explore.workbench;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * An ordered list of chosen things, and a chooser of what could be added.
 *
 * <p>Written three times before this — the identity key, an aggregate's grouping pairs,
 * a class's contextual representations — and each copy carried the same two defects,
 * because both follow from using a Swing control's SELECTION as configuration.
 *
 * <p><b>Clicking a row is inspection.</b> A multi-select list replaces its selection on a
 * plain click, so clicking one row to read it deselected every other, and applying wrote
 * the class with only that one. On Nobel that turned a two-part key into a one-part key,
 * silently, from a click that looked like looking. Here the list HOLDS the choice;
 * selecting a row only says what Remove and the move buttons would act on.
 *
 * <p><b>Nothing chosen is a state.</b> A JComboBox selects its first item the moment one
 * is added, so Add acted on something nobody had picked — after removing a pair, the box
 * showed it again and Add put it straight back. A null entry leads the list and Add stays
 * disabled until a real choice is made.
 *
 * @param <T> what is being chosen; its {@code toString} is what the reader sees
 */
final class OrderedChoiceList<T> extends JPanel {

    private final DefaultListModel<T> chosenModel = new DefaultListModel<>();
    private final JList<T> chosen = new JList<>(chosenModel);
    private final JComboBox<T> available = new JComboBox<>();
    private final JButton add = new JButton("Add");
    private final JButton remove = new JButton("Remove");
    private final JButton up = new JButton("Up");
    private final JButton down = new JButton("Down");
    private Runnable onChange = () -> { };

    /**
     * @param ordered whether position carries meaning. It does for an identity key —
     *                an identifier joins a key's values IN order — and does not for a
     *                set, where offering Up and Down would imply a difference there is
     *                none of.
     */
    OrderedChoiceList(boolean ordered) {
        super(new BorderLayout(4, 4));
        chosen.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chosen.setVisibleRowCount(4);
        chosen.addListSelectionListener(event -> refreshEnablement());
        add.addActionListener(event -> addChosen());
        remove.addActionListener(event -> removeSelected());
        up.addActionListener(event -> move(-1));
        down.addActionListener(event -> move(1));
        available.addActionListener(event -> refreshEnablement());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.add(add);
        buttons.add(remove);
        if (ordered) {
            buttons.add(up);
            buttons.add(down);
        }
        buttons.add(Box.createVerticalStrut(8));
        JLabel label = new JLabel("Available:");
        label.setToolTipText("What could be added. Add puts the chosen one into the "
                + "list; Remove takes the selected row out of it.");
        buttons.add(label);
        buttons.add(available);

        add(new JScrollPane(chosen), BorderLayout.CENTER);
        add(buttons, BorderLayout.EAST);
        refreshEnablement();
    }

    /**
     * How a choice reads, in BOTH halves.
     *
     * <p>One rule, because the list and the chooser show the same things: describing them
     * only in the list is how a row and the entry that would add it came to read
     * differently — the same class named two ways in one control.
     */
    void describe(java.util.function.Function<T, String> text) {
        java.util.function.Function<T, String> describe =
                text == null ? String::valueOf : text;
        javax.swing.ListCellRenderer<Object> renderer =
                new javax.swing.DefaultListCellRenderer() {
                    @Override
                    public java.awt.Component getListCellRendererComponent(
                            JList<?> list, Object value, int index,
                            boolean selected, boolean focus) {
                        JLabel label = (JLabel) super.getListCellRendererComponent(
                                list, value, index, selected, focus);
                        @SuppressWarnings("unchecked") T item = (T) value;
                        label.setText(value == null ? " " : describe.apply(item));
                        return label;
                    }
                };
        chosen.setCellRenderer(renderer);
        available.setRenderer(renderer);
    }

    void title(String title) {
        setBorder(BorderFactory.createTitledBorder(title));
    }

    void onChange(Runnable listener) {
        onChange = listener == null ? () -> { } : listener;
    }

    /** What is chosen, in order — the list's CONTENTS, never its selection. */
    List<T> chosen() {
        List<T> values = new ArrayList<>();
        for (int i = 0; i < chosenModel.size(); i++) values.add(chosenModel.get(i));
        return values;
    }

    /**
     * Replaces both halves: what is chosen, and what remains addable.
     *
     * <p>Anything already chosen is dropped from the offer, so a thing cannot be added
     * twice — a repeated key component is not a tighter key, it is the same partition
     * computed twice with its order made ambiguous.
     */
    void show(List<T> alreadyChosen, List<T> addable) {
        chosenModel.clear();
        if (alreadyChosen != null) alreadyChosen.forEach(chosenModel::addElement);
        available.removeAllItems();
        available.addItem(null);
        if (addable != null) {
            for (T candidate : addable) {
                if (candidate != null && !chosenModel.contains(candidate)) {
                    available.addItem(candidate);
                }
            }
        }
        available.setSelectedItem(null);
        refreshEnablement();
    }

    /**
     * How much of this list a reader may change — three states, because two cannot say
     * what an identity editor needs to say.
     */
    enum Mode {
        /** Add, remove and reorder freely. */
        EDITABLE,
        /**
         * The contents are a DEFAULT that adding replaces. A Source class shows "source
         * identity" until a field key is chosen; removing it would leave the class with
         * no identity at all, which is not what the reader means by removing a default.
         */
        REPLACED_BY_ADDING,
        /** Supplied by production and not a choice — an owned part's owner plus site. */
        FIXED
    }

    private Mode mode = Mode.EDITABLE;

    void mode(Mode value) {
        mode = value == null ? Mode.EDITABLE : value;
        chosen.setEnabled(mode != Mode.FIXED);
        available.setEnabled(mode != Mode.FIXED);
        refreshEnablement();
    }

    private void addChosen() {
        T candidate = available.getItemAt(available.getSelectedIndex());
        if (candidate == null || chosenModel.contains(candidate)) return;
        // Adding to a default REPLACES it: the shown component was never chosen, and
        // keeping it beside the first real choice would silently make a compound key
        // out of one selection.
        if (mode == Mode.REPLACED_BY_ADDING) chosenModel.clear();
        chosenModel.addElement(candidate);
        available.removeItem(candidate);
        available.setSelectedItem(null);
        refreshEnablement();
        onChange.run();
    }

    private void removeSelected() {
        int index = chosen.getSelectedIndex();
        if (index < 0) return;
        available.addItem(chosenModel.remove(index));
        refreshEnablement();
        onChange.run();
    }

    private void move(int by) {
        int index = chosen.getSelectedIndex();
        int target = index + by;
        if (index < 0 || target < 0 || target >= chosenModel.size()) return;
        chosenModel.add(target, chosenModel.remove(index));
        chosen.setSelectedIndex(target);
        onChange.run();
    }

    /** Every button says whether it can do anything, before it is pressed. */
    private void refreshEnablement() {
        int index = chosen.getSelectedIndex();
        boolean modifiable = mode == Mode.EDITABLE;
        add.setEnabled(mode != Mode.FIXED && available.getSelectedItem() != null);
        remove.setEnabled(modifiable && index >= 0);
        up.setEnabled(modifiable && index > 0);
        down.setEnabled(modifiable && index >= 0 && index < chosenModel.size() - 1);
    }
}
