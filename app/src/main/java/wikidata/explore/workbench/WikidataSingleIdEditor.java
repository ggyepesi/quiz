package wikidata.explore.workbench;

import wikidata.ui.WikidataIdText;
import wikidata.ui.WikidataLabels;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.Locale;

/**
 * Compact editor for one QID or PID. The configured value is painted once as its
 * labelled link. Change replaces that row with an inline draft; Set writes the one
 * backing text document observed by the owning panel.
 */
final class WikidataSingleIdEditor extends JPanel implements TextBackedEditor {

    private static final String VALUE = "value";
    private static final String EDIT = "edit";

    private final JTextComponent storage;
    private final WikidataIdListEditor.Kind kind;
    private final CardLayout cards = new CardLayout();
    private final WikidataIdText value = WikidataIdLines.text();
    private final JTextField draft = new JTextField(8);
    private final JButton change = new JButton("Change");
    private final JButton remove = new JButton("Remove");
    private final JButton set = new JButton("Set");
    private final JButton cancel = new JButton("Cancel");
    private final DocumentListener storageListener = new StorageListener();
    private Document listenedDocument;
    private boolean editorEditable = true;
    private boolean editing;

    WikidataSingleIdEditor(JTextComponent storage, WikidataIdListEditor.Kind kind) {
        super();
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        this.kind = kind == null ? WikidataIdListEditor.Kind.ID : kind;
        setLayout(cards);

        JPanel shown = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        shown.setOpaque(false);
        shown.add(value);
        shown.add(change);
        shown.add(remove);
        JPanel edited = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        edited.setOpaque(false);
        edited.add(draft);
        edited.add(set);
        edited.add(cancel);
        add(shown, VALUE);
        add(edited, EDIT);

        change.addActionListener(event -> beginEdit());
        remove.addActionListener(event -> storage.setText(""));
        set.addActionListener(event -> commitDraft());
        cancel.addActionListener(event -> cancelEdit());
        draft.addActionListener(event -> commitDraft());
        draft.getDocument().addDocumentListener(listener(this::refreshActions));

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
                copyTooltip());
        bindDocument();
        copyTooltip();
        setVisible(storage.isVisible());
    }

    @Override public void setEditorEditable(boolean editable) {
        editorEditable = editable;
        storage.setEditable(editable);
        if (!editable && valid(storage.getText())) editing = false;
        showCard();
        refreshActions();
    }

    JTextField draftField() {
        return draft;
    }

    JButton setButton() {
        return set;
    }

    JButton changeButton() {
        return change;
    }

    JButton removeButton() {
        return remove;
    }

    WikidataIdText valueText() {
        return value;
    }

    @Override public JTextComponent storage() {
        return storage;
    }

    private void bindDocument() {
        if (listenedDocument != null) listenedDocument.removeDocumentListener(storageListener);
        listenedDocument = storage.getDocument();
        listenedDocument.addDocumentListener(storageListener);
        storageChanged();
    }

    private final class StorageListener implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent event) { storageChanged(); }
        @Override public void removeUpdate(DocumentEvent event) { storageChanged(); }
        @Override public void changedUpdate(DocumentEvent event) { storageChanged(); }
    }

    private void storageChanged() {
        String id = normalized(storage.getText());
        value.setPlainText(id);
        if (valid(id)) {
            WikidataLabels.request(java.util.List.of(id));
            if (!editing) draft.setText(id);
        } else {
            draft.setText(storage.getText() == null ? "" : storage.getText().trim());
        }
        showCard();
        refreshActions();
    }

    private void beginEdit() {
        if (!canEdit()) return;
        draft.setText(normalized(storage.getText()));
        editing = true;
        showCard();
        draft.requestFocusInWindow();
        draft.selectAll();
    }

    private void commitDraft() {
        String id = normalized(draft.getText());
        if (!valid(id) || !canEdit()) {
            refreshActions();
            return;
        }
        editing = false;
        storage.setText(id);
        showCard();
    }

    private void cancelEdit() {
        if (!valid(storage.getText())) return;
        editing = false;
        draft.setText(normalized(storage.getText()));
        showCard();
    }

    private void showCard() {
        boolean hasValue = valid(storage.getText());
        cards.show(this, !editing && hasValue ? VALUE : EDIT);
    }

    private void refreshActions() {
        boolean editable = canEdit();
        EditableComponents.setEditable(draft, editable);
        set.setEnabled(editable && valid(draft.getText()));
        cancel.setEnabled(editable && valid(storage.getText()));
        change.setEnabled(editable);
        remove.setEnabled(editable && valid(storage.getText()));
        String help = "Enter a valid " + kind.name + ".";
        draft.setToolTipText(valid(draft.getText()) || draft.getText().isBlank()
                ? storage.getToolTipText() : help);
    }

    private boolean canEdit() {
        return editorEditable && storage.isEnabled() && storage.isEditable();
    }

    private boolean valid(String text) {
        return kind.accepts(normalized(text));
    }

    private static String normalized(String text) {
        return text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
    }

    private void copyTooltip() {
        setToolTipText(storage.getToolTipText());
        value.setToolTipText(storage.getToolTipText());
        draft.setToolTipText(storage.getToolTipText());
    }

    private static DocumentListener listener(Runnable action) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            @Override public void changedUpdate(DocumentEvent event) { action.run(); }
        };
    }
}
