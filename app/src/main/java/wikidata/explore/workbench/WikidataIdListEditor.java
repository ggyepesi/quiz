package wikidata.explore.workbench;

import wikidata.WikidataIds;
import wikidata.ui.WikidataLabels;
import wikidata.ui.WikidataLinks;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One editor for a configured list of Wikidata identifiers.
 *
 * <p>The bound text component remains the configuration storage used by the owning
 * panel, but is deliberately not painted. Painting it beside labelled links produced
 * two representations of one fact and made a modest QID list wider than its form.
 * This control instead paints each configured identifier once, as
 * {@code label (QID/PID)}, and changes that list only through Add and Remove.
 *
 * <p>The backing component is retained rather than mirrored into a second model so
 * existing document listeners, dirty tracking and apply code continue to observe the
 * same source of truth. Programmatic {@code setText} calls rebuild the visible list;
 * Add and Remove write the same document.
 */
final class WikidataIdListEditor extends JPanel implements TextBackedEditor {

    enum Kind {
        QID("QID") {
            @Override boolean accepts(String id) { return WikidataIds.isQid(id); }
        },
        PID("PID") {
            @Override boolean accepts(String id) { return WikidataIds.isPid(id); }
        },
        ID("QID or PID") {
            @Override boolean accepts(String id) { return WikidataIds.isId(id); }
        };

        final String name;

        Kind(String name) {
            this.name = name;
        }

        abstract boolean accepts(String id);
    }

    private final JTextComponent storage;
    private final Kind kind;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> ids = new JList<>(model);
    private final JTextField draft = new JTextField(12);
    private final JButton add = new JButton("Add");
    private final JButton remove = new JButton("Remove");
    private final JButton open = new JButton("Open");
    private final Runnable repaintLabels = ids::repaint;
    private boolean editorEditable = true;
    private boolean writingStorage;
    private Document listenedDocument;
    private final DocumentListener storageListener = new StorageListener();

    WikidataIdListEditor(JTextComponent storage, Kind kind) {
        super(new BorderLayout(4, 3));
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        this.kind = kind == null ? Kind.ID : kind;

        ids.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ids.setVisibleRowCount(3);
        ids.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                String id = value == null ? "" : value.toString();
                setText(selected
                        ? WikidataLinks.labelledPlain(id)
                        : "<html>" + WikidataLinks.labelledLink(id) + "</html>");
                return this;
            }
        });
        ids.setToolTipText("Select a row to remove it; double-click to open Wikidata.");
        ids.addListSelectionListener(event -> refreshActions());
        ids.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) openSelected();
            }
        });

        draft.setToolTipText("Enter one or more " + this.kind.name
                + "s, separated by spaces or commas, then press Add.");
        draft.getDocument().addDocumentListener(listener(this::refreshActions));
        draft.addActionListener(event -> addDraft());
        add.addActionListener(event -> addDraft());
        remove.addActionListener(event -> removeSelected());
        open.addActionListener(event -> openSelected());

        JPanel entry = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        entry.setOpaque(false);
        entry.add(draft);
        entry.add(add);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        actions.add(remove);
        actions.add(open);

        JScrollPane scroll = new JScrollPane(ids);
        scroll.setPreferredSize(new Dimension(300, 64));
        add(entry, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);

        storage.addPropertyChangeListener("document", event -> bindDocument());
        storage.addPropertyChangeListener("enabled", event -> refreshActions());
        storage.addPropertyChangeListener("editable", event -> refreshActions());
        storage.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentShown(java.awt.event.ComponentEvent event) {
                setVisible(true);
            }
            @Override public void componentHidden(java.awt.event.ComponentEvent event) {
                setVisible(false);
            }
        });
        storage.addPropertyChangeListener("ToolTipText", event ->
                setToolTipText(storage.getToolTipText()));
        setToolTipText(storage.getToolTipText());
        bindDocument();
        setVisible(storage.isVisible());
    }

    @Override public void addNotify() {
        super.addNotify();
        WikidataLabels.onChange(repaintLabels);
        requestLabels();
    }

    @Override public void removeNotify() {
        WikidataLabels.removeOnChange(repaintLabels);
        super.removeNotify();
    }

    /** Called by the shared editor-lock traversal instead of enabling each button. */
    @Override public void setEditorEditable(boolean value) {
        editorEditable = value;
        storage.setEditable(value);
        refreshActions();
    }

    List<String> shownIds() {
        List<String> shown = new ArrayList<>(model.size());
        for (int i = 0; i < model.size(); i++) shown.add(model.get(i));
        return shown;
    }

    JTextField draftField() {
        return draft;
    }

    JButton addButton() {
        return add;
    }

    JButton removeButton() {
        return remove;
    }

    JList<String> idList() {
        return ids;
    }

    @Override public JTextComponent storage() {
        return storage;
    }

    private void bindDocument() {
        if (listenedDocument != null) {
            listenedDocument.removeDocumentListener(storageListener);
        }
        listenedDocument = storage.getDocument();
        listenedDocument.addDocumentListener(storageListener);
        reloadFromStorage();
    }

    private final class StorageListener implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent event) { changed(); }
        @Override public void removeUpdate(DocumentEvent event) { changed(); }
        @Override public void changedUpdate(DocumentEvent event) { changed(); }
        private void changed() {
            if (!writingStorage) reloadFromStorage();
        }
    }

    private void reloadFromStorage() {
        String selected = ids.getSelectedValue();
        model.clear();
        for (String id : tokens(storage.getText())) model.addElement(id);
        if (selected != null) ids.setSelectedValue(selected, true);
        requestLabels();
        refreshActions();
    }

    private void requestLabels() {
        WikidataLabels.request(shownIds().stream().filter(kind::accepts).toList());
    }

    private void addDraft() {
        List<String> candidates = tokens(draft.getText());
        if (candidates.isEmpty() || candidates.stream().anyMatch(id -> !kind.accepts(id))) {
            refreshActions();
            return;
        }
        Set<String> next = new LinkedHashSet<>(shownIds());
        next.addAll(candidates);
        writeStorage(next);
        draft.setText("");
    }

    private void removeSelected() {
        String selected = ids.getSelectedValue();
        if (selected == null) return;
        List<String> next = shownIds();
        next.remove(selected);
        writeStorage(next);
    }

    private void openSelected() {
        WikidataLinks.open(ids.getSelectedValue());
    }

    private void writeStorage(Iterable<String> values) {
        List<String> next = new ArrayList<>();
        values.forEach(next::add);
        writingStorage = true;
        try {
            storage.setText(String.join(" ", next));
        } finally {
            writingStorage = false;
        }
        reloadFromStorage();
    }

    private void refreshActions() {
        boolean editable = editorEditable && storage.isEnabled() && storage.isEditable();
        List<String> candidates = tokens(draft.getText());
        boolean validDraft = !candidates.isEmpty()
                && candidates.stream().allMatch(kind::accepts);
        EditableComponents.setEditable(draft, editable);
        // Selection and opening a link are inspection, not configuration changes.
        ids.setEnabled(storage.isEnabled());
        add.setEnabled(editable && validDraft);
        remove.setEnabled(editable && ids.getSelectedIndex() >= 0);
        open.setEnabled(ids.getSelectedValue() != null
                && WikidataLinks.isId(ids.getSelectedValue()));
        draft.setToolTipText(validDraft || draft.getText().isBlank()
                ? "Enter one or more " + kind.name
                        + "s, separated by spaces or commas, then press Add."
                : "Every value must be a valid " + kind.name + ".");
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : text.trim().split("[,;\\s]+")) {
            if (!token.isBlank()) values.add(token.toUpperCase(Locale.ROOT));
        }
        return new ArrayList<>(values);
    }

    private static DocumentListener listener(Runnable action) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(DocumentEvent event) { action.run(); }
        };
    }
}
