package quiz.curation.ui;

import objectview.utils.BrowserLauncher;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import quiz.enrichment.ui.WikidataIdentitySearchPanel;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reusable editor for approved links from one domain object to external records.
 * Links are saved in the curation sidecar, not in generated domain objects.
 */
public final class SourceManagerDialog extends JDialog {

    private final ManualCuration curation;
    private final String type;
    private final String targetId;
    private final String displayName;
    private final Runnable onChanged;
    private final SwingQueryRunner queryRunner;
    private final SourcePrefixStore prefixes = new SourcePrefixStore();
    private final List<IdentityLink> links = new ArrayList<>();
    private final LinksModel model = new LinksModel();
    private final JTable table = new JTable(model);
    private final JComboBox<String> kind = new JComboBox<>();
    private final JTextField sourceId = new JTextField(24);
    private final JTextField prefix = new JTextField(32);
    private final JTextField recordUrl = new JTextField(32);
    private final JTextField canonicalName = new JTextField(32);
    private final JCheckBox rememberPrefix =
            new JCheckBox("Remember this prefix for this source kind", true);
    private final JLabel validation = new JLabel(" ");
    private IdentityLink editing;

    private SourceManagerDialog(Component parent, ManualCuration curation,
                                String type, String targetId, String displayName,
                                SwingQueryRunner queryRunner, Runnable onChanged) {
        super(owner(parent), "Sources — " + displayName, ModalityType.APPLICATION_MODAL);
        this.curation = curation;
        this.type = type;
        this.targetId = targetId;
        this.displayName = displayName;
        this.queryRunner = queryRunner;
        this.onChanged = onChanged == null ? () -> { } : onChanged;

        setLayout(new BorderLayout(8, 8));
        add(help(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        loadLinks();
        clearForm();
        prefillWikidataIdentity();
        setMinimumSize(new Dimension(760, 610));
        pack();
        setLocationRelativeTo(parent);
    }

    public static void show(Component parent, ManualCuration curation,
                            String type, String targetId, String displayName,
                            SwingQueryRunner queryRunner, Runnable onChanged) {
        if (curation == null || targetId == null || targetId.isBlank()) {
            JOptionPane.showMessageDialog(parent,
                    "This object has no stable identifier, so a source cannot be saved for it.");
            return;
        }
        new SourceManagerDialog(parent, curation, type, targetId,
                displayName == null ? targetId : displayName,
                queryRunner, onChanged).setVisible(true);
    }

    private Component help() {
        JTextArea text = new JTextArea(
                "Link this object to the exact record that describes it. This helps image and "
                + "data discovery distinguish name variants and people with the same name.\n\n"
                + "Use the record page (for example a laureate or Wikidata entity page), not "
                + "the source homepage. The source ID is the identifier used by that site. "
                + "A remembered URL prefix makes later entries a prefix + ID operation.");
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        return text;
    }

    private Component content() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                edit(links.get(table.convertRowIndexToModel(table.getSelectedRow())));
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(720, 155));
        panel.add(scroll, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        field(form, row++, "Source kind", kind);
        kind.setEditable(true);
        for (String value : prefixes.kinds()) kind.addItem(value);
        kind.addActionListener(e -> loadPrefix());
        field(form, row++, "Source ID", sourceId);
        field(form, row++, "URL prefix", prefix);

        JPanel build = new JPanel(new BorderLayout(5, 0));
        build.add(recordUrl, BorderLayout.CENTER);
        JButton buildButton = new JButton("Build from prefix + ID");
        buildButton.addActionListener(e ->
                recordUrl.setText(SourcePrefixStore.build(prefix.getText(), sourceId.getText())));
        build.add(buildButton, BorderLayout.EAST);
        field(form, row++, "Exact record URL", build);
        field(form, row++, "Canonical name", canonicalName);
        canonicalName.setToolTipText(
                "The name used by this source; it may differ from the domain object's name.");
        GridBagConstraints remember = constraints(1, row++);
        remember.anchor = GridBagConstraints.WEST;
        form.add(rememberPrefix, remember);
        GridBagConstraints error = constraints(1, row++);
        error.anchor = GridBagConstraints.WEST;
        validation.setForeground(new java.awt.Color(170, 45, 45));
        form.add(validation, error);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton save = new JButton("Save source");
        save.addActionListener(e -> save());
        JButton fresh = new JButton("New");
        fresh.addActionListener(e -> clearForm());
        JButton remove = new JButton("Remove");
        remove.addActionListener(e -> remove());
        JButton open = new JButton("Open URL ↗");
        open.addActionListener(e -> open());
        JButton findWikidata = new JButton("Find Wikidata QID…");
        findWikidata.setToolTipText(
                "Search by name, review labels and descriptions, then use the selected QID");
        findWikidata.addActionListener(e -> findWikidata());
        actions.add(findWikidata);
        actions.add(save);
        actions.add(fresh);
        actions.add(remove);
        actions.add(open);
        GridBagConstraints buttons = constraints(1, row);
        buttons.anchor = GridBagConstraints.WEST;
        form.add(actions, buttons);
        panel.add(form, BorderLayout.CENTER);
        return panel;
    }

    private void findWikidata() {
        if (queryRunner == null) {
            JOptionPane.showMessageDialog(this, "Wikidata search is not available here.");
            return;
        }
        String name = canonicalName.getText().isBlank()
                ? displayName : canonicalName.getText().trim();
        WikidataIdentitySearchPanel.showDialog(this, queryRunner, name, (qid, label) -> {
            kind.setSelectedItem("Wikidata");
            sourceId.setText(qid);
            prefix.setText(prefixes.prefix("Wikidata"));
            recordUrl.setText(SourcePrefixStore.build(prefix.getText(), qid));
            canonicalName.setText(label == null || label.isBlank() ? displayName : label);
            validation.setForeground(new java.awt.Color(80, 80, 80));
            validation.setText("Wikidata candidate selected. Save source to approve it.");
        });
    }

    private Component footer() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        panel.add(close);
        return panel;
    }

    private void save() {
        String sourceKind = editorText(kind).trim();
        String id = sourceId.getText().trim();
        String url = recordUrl.getText().trim();
        String error = sourceKind.isBlank() ? "Choose or enter a source kind."
                : SourcePrefixStore.validationError(url);
        if (error != null) {
            validation.setForeground(new java.awt.Color(170, 45, 45));
            validation.setText(error);
            return;
        }
        IdentityLink replacement = new IdentityLink(type, targetId, sourceKind, id, url,
                canonicalName.getText().trim(), "manual");
        List<IdentityLink> before = curation.identityLinks();
        if (editing != null) {
            curation.removeIdentityLink(editing.type(), editing.targetId(), editing.sourceKind());
        }
        curation.putIdentityLink(replacement);
        if (!persist(before)) return;

        String remembered = prefix.getText().trim();
        if (remembered.isBlank()) {
            remembered = SourcePrefixStore.inferPrefix(url, id);
        }
        if (rememberPrefix.isSelected()) {
            prefixes.remember(sourceKind, remembered);
        }
        loadLinks();
        edit(replacement);
        onChanged.run();
        validation.setText("Saved. This source will be used by the next enrichment preview.");
        validation.setForeground(new java.awt.Color(35, 120, 55));
    }

    private void remove() {
        if (editing == null) {
            validation.setForeground(new java.awt.Color(170, 45, 45));
            validation.setText("Select a saved source first.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Remove the " + editing.sourceKind() + " link for " + displayName + "?",
                "Remove source", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        List<IdentityLink> before = curation.identityLinks();
        curation.removeIdentityLink(editing.type(), editing.targetId(), editing.sourceKind());
        if (!persist(before)) return;
        loadLinks();
        clearForm();
        onChanged.run();
    }

    private boolean persist(List<IdentityLink> before) {
        try {
            curation.save();
            return true;
        } catch (IOException ex) {
            restore(before);
            JOptionPane.showMessageDialog(this, "Could not save sources: " + ex.getMessage(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void restore(List<IdentityLink> before) {
        for (IdentityLink link : curation.identityLinks()) {
            curation.removeIdentityLink(link.type(), link.targetId(), link.sourceKind());
        }
        for (IdentityLink link : before) curation.putIdentityLink(link);
    }

    private void open() {
        String error = SourcePrefixStore.validationError(recordUrl.getText());
        if (error != null) {
            validation.setForeground(new java.awt.Color(170, 45, 45));
            validation.setText(error);
        } else {
            BrowserLauncher.open(recordUrl.getText());
        }
    }

    private void loadLinks() {
        links.clear();
        for (IdentityLink link : curation.identityLinks()) {
            if (Objects.equals(type, link.type()) && Objects.equals(targetId, link.targetId())) {
                links.add(link);
            }
        }
        model.fireTableDataChanged();
    }

    private void edit(IdentityLink link) {
        editing = link;
        kind.setSelectedItem(link.sourceKind());
        sourceId.setText(nullToEmpty(link.sourceId()));
        recordUrl.setText(nullToEmpty(link.recordUrl()));
        canonicalName.setText(nullToEmpty(link.canonicalName()));
        String inferred = SourcePrefixStore.inferPrefix(link.recordUrl(), link.sourceId());
        prefix.setText(inferred.isBlank() ? prefixes.prefix(link.sourceKind()) : inferred);
        validation.setText("Editing saved " + link.sourceKind() + " source.");
        validation.setForeground(new java.awt.Color(80, 80, 80));
    }

    private void clearForm() {
        editing = null;
        table.clearSelection();
        if (kind.getItemCount() > 0) kind.setSelectedIndex(0);
        sourceId.setText("");
        recordUrl.setText("");
        canonicalName.setText(displayName);
        loadPrefix();
        validation.setText("Enter a source ID and exact record URL.");
        validation.setForeground(new java.awt.Color(80, 80, 80));
    }

    /** A domain QID is already an exact Wikidata identity; make the approval a
     *  one-click save while retaining "Find Wikidata QID…" for local/non-QID IDs. */
    private void prefillWikidataIdentity() {
        if (targetId == null || !targetId.matches("Q\\d+")
                || links.stream().anyMatch(link ->
                "Wikidata".equalsIgnoreCase(link.sourceKind()))) {
            return;
        }
        kind.setSelectedItem("Wikidata");
        sourceId.setText(targetId);
        prefix.setText(prefixes.prefix("Wikidata"));
        recordUrl.setText(SourcePrefixStore.build(prefix.getText(), targetId));
        canonicalName.setText(displayName);
        validation.setText(
                "The object's Wikidata QID is pre-filled. Save source to approve it.");
        validation.setForeground(new java.awt.Color(80, 80, 80));
    }

    private void loadPrefix() {
        String selected = editorText(kind);
        prefix.setText(prefixes.prefix(selected));
    }

    private static String editorText(JComboBox<String> combo) {
        Object value = combo.isEditable()
                ? combo.getEditor().getItem() : combo.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private static void field(JPanel panel, int row, String label, Component component) {
        GridBagConstraints l = constraints(0, row);
        l.anchor = GridBagConstraints.LINE_END;
        panel.add(new JLabel(label + ":"), l);
        GridBagConstraints f = constraints(1, row);
        f.weightx = 1;
        f.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, f);
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.insets = new Insets(3, 4, 3, 4);
        return c;
    }

    private static Window owner(Component parent) {
        return parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private final class LinksModel extends AbstractTableModel {
        private final String[] columns = {"Source", "Record ID", "Canonical name", "Record URL"};

        @Override public int getRowCount() { return links.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override public Object getValueAt(int row, int column) {
            IdentityLink link = links.get(row);
            return switch (column) {
                case 0 -> link.sourceKind();
                case 1 -> link.sourceId();
                case 2 -> link.canonicalName();
                default -> link.recordUrl();
            };
        }
    }
}
