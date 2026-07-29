package quiz.enrichment.ui;

import quiz.enrichment.ChosenProperty;
import quiz.enrichment.PropertyOption;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Lets the user pick the Wikidata property that sources a field, from the sample entity's
 * REAL properties (label + example value). A filter box narrows the list; the name-derived
 * default (if any) is pre-selected so common cases are one click. This replaces the
 * hardcoded name→property map with a choice made from data.
 */
public final class FieldPropertyPickerPanel extends JPanel {

    /** Show the picker modally; {@code onChosen} gets the choice (absent on cancel). */
    public static void showDialog(
            Component owner,
            String title,
            String prompt,
            String field,
            List<PropertyOption> options,
            String suggestedPid,
            Consumer<ChosenProperty> onChosen) {
        createDialog(owner, title, prompt, options, suggestedPid, onChosen,
                Dialog.ModalityType.APPLICATION_MODAL).setVisible(true);
    }

    public static JDialog showModeless(
            Component owner, String title, String prompt, String field,
            List<PropertyOption> options, String suggestedPid,
            Consumer<ChosenProperty> onChosen) {
        JDialog dialog = createDialog(owner, title, prompt, options, suggestedPid, onChosen,
                Dialog.ModalityType.MODELESS);
        dialog.setVisible(true);
        return dialog;
    }

    private static JDialog createDialog(
            Component owner, String title, String prompt,
            List<PropertyOption> options, String suggestedPid,
            Consumer<ChosenProperty> onChosen, Dialog.ModalityType modality) {
        Window window = SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, title, modality);
        Consumer<ChosenProperty> handler = onChosen == null ? ignored -> { } : onChosen;
        java.util.concurrent.atomic.AtomicBoolean completed =
                new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<ChosenProperty> finish = chosen -> {
            if (completed.compareAndSet(false, true)) {
                handler.accept(chosen);
                dialog.dispose();
            }
        };
        FieldPropertyPickerPanel panel =
                new FieldPropertyPickerPanel(prompt, options, suggestedPid, finish);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                finish.accept(new ChosenProperty("", ""));
            }
        });
        dialog.add(panel);
        dialog.setSize(560, 560);
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    private final List<PropertyOption> options;
    private final DefaultListModel<PropertyOption> model = new DefaultListModel<>();
    private final JList<PropertyOption> list = new JList<>(model);

    private FieldPropertyPickerPanel(
            String prompt,
            List<PropertyOption> options,
            String suggestedPid,
            Consumer<ChosenProperty> onChosen) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        this.options = options == null ? List.of() : options;

        JTextField filter = new JTextField();
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) {
                repopulate(filter.getText());
            }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) {
                repopulate(filter.getText());
            }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {
                repopulate(filter.getText());
            }
        });

        JPanel north = new JPanel(new BorderLayout(4, 4));
        north.add(new JLabel("<html>" + html(prompt) + "</html>"), BorderLayout.NORTH);
        north.add(filter, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new OptionRenderer());
        add(new JScrollPane(list), BorderLayout.CENTER);

        repopulate("");
        preselect(suggestedPid);

        add(buttons(onChosen), BorderLayout.SOUTH);
    }

    private JComponent buttons(Consumer<ChosenProperty> onChosen) {
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> onChosen.accept(new ChosenProperty("", "")));
        JButton choose = new JButton("Use property");
        choose.addActionListener(e -> {
            PropertyOption selected = list.getSelectedValue();
            onChosen.accept(selected == null
                    ? new ChosenProperty("", "")
                    : new ChosenProperty(selected.pid(), selected.label()));
        });
        list.addListSelectionListener(e -> choose.setEnabled(list.getSelectedValue() != null));
        choose.setEnabled(list.getSelectedValue() != null);

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.add(cancel);
        panel.add(choose);
        return panel;
    }

    private void repopulate(String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        PropertyOption keep = list.getSelectedValue();
        model.clear();
        for (PropertyOption option : options) {
            if (needle.isEmpty()
                    || option.label().toLowerCase(Locale.ROOT).contains(needle)
                    || option.pid().toLowerCase(Locale.ROOT).contains(needle)) {
                model.addElement(option);
            }
        }
        if (keep != null && model.contains(keep)) {
            list.setSelectedValue(keep, true);
        }
    }

    private void preselect(String suggestedPid) {
        if (suggestedPid == null || suggestedPid.isBlank()) {
            return;
        }
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).pid().equalsIgnoreCase(suggestedPid)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private static String html(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class OptionRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof PropertyOption option) {
                String example = option.example().isBlank() ? ""
                        : "  =  " + option.example();
                setText("<html><b>" + html(option.label()) + "</b> <font color=gray>("
                        + html(option.pid()) + ")</font>" + html(example) + "</html>");
            }
            return this;
        }
    }
}
