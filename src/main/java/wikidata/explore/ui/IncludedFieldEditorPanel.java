package wikidata.explore.ui;

import wikidata.explore.CommonsMedia;
import wikidata.explore.tree.RuleIncludedField;
import wikidata.explore.tree.RuleNode;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Editor for a node's list of {@link RuleIncludedField}s.
 *
 * Layout:
 *
 *   ┌─ Included fields ──────────────────────────────────────────┐
 *   │  [Field name____] [PID___] [Label___________] [Kind▼] [✓opt] │
 *   │  [Add image P18]                                           │
 *   ├────────────────────────────────┬───────────────────────────┤
 *   │ Configured fields:             │ [Add]                     │
 *   │  image ← image (P18)          │ [Replace selected]        │
 *   │  ...                          │ [Remove selected]         │
 *   └────────────────────────────────┴───────────────────────────┘
 */
public class IncludedFieldEditorPanel extends JPanel {

    private final DefaultListModel<RuleIncludedField> model =
            new DefaultListModel<>();

    private final JList<RuleIncludedField> list =
            new JList<>(model);

    // -- Editor row fields ------------------------------------------------
    private final JTextField fieldNameField = new JTextField(10);
    private final JTextField pidField       = new JTextField(6);
    private final JTextField labelField     = new JTextField(12);

    private final JComboBox<RuleIncludedField.FieldKind> kindBox =
            new JComboBox<>(RuleIncludedField.FieldKind.values());

    private final JCheckBox optionalBox = new JCheckBox("optional", true);

    // -- Action buttons ---------------------------------------------------
    private final JButton addButton         = new JButton("Add");
    private final JButton replaceButton     = new JButton("Replace selected");
    private final JButton removeButton      = new JButton("Remove selected");
    private final JButton addImageButton    = new JButton("Add image P18");

    public IncludedFieldEditorPanel() {
        super(new BorderLayout(4, 4));
        buildUi();
        wireActions();
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public List<RuleIncludedField> fields() {
        List<RuleIncludedField> out = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) out.add(model.get(i));
        return out;
    }

    public void setFields(List<RuleIncludedField> fields) {
        model.clear();
        if (fields != null)
            for (RuleIncludedField f : fields)
                if (f != null) model.addElement(f);
        if (!model.isEmpty()) list.setSelectedIndex(0);
    }

    /**
     * Pre-fills the editor from a property selected in the property browser.
     * Auto-detects MEDIA kind for known image PIDs (P18, P41, P94 …).
     */
    public void useProperty(String pid, String label) {
        String cleaned = RuleNode.cleanPid(pid);
        pidField.setText(cleaned);
        labelField.setText(label == null ? "" : label);

        if (fieldNameField.getText().trim().isBlank())
            fieldNameField.setText(toFieldName(label, pid));

        if (CommonsMedia.isMediaProperty(cleaned))
            kindBox.setSelectedItem(RuleIncludedField.FieldKind.MEDIA);
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void buildUi() {
        setBorder(BorderFactory.createTitledBorder("Included fields"));

        // -- Editor row --------------------------------------------------
        JPanel editorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        editorRow.add(new JLabel("Field:"));
        editorRow.add(fieldNameField);
        editorRow.add(new JLabel("PID:"));
        editorRow.add(pidField);
        editorRow.add(new JLabel("Label:"));
        editorRow.add(labelField);
        editorRow.add(new JLabel("Kind:"));
        editorRow.add(kindBox);

        optionalBox.setToolTipText(
                "<html>When checked: OPTIONAL { ?value wdt:Pxx ?field }<br>"
                + "The entity still appears even if it has no value for this property.<br>"
                + "When unchecked: ?value wdt:Pxx ?field (required triple — entity "
                + "is excluded if the property is missing).</html>");
        editorRow.add(optionalBox);
        editorRow.add(addImageButton);

        // -- List with header + side buttons -----------------------------
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(4);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setColumnHeaderView(
                new JLabel("  Configured fields  (select to edit above)"));

        // Buttons stacked beside the list
        addButton.setToolTipText(
                "Add a new field using the values entered above.");
        replaceButton.setToolTipText(
                "Overwrite the selected field in the list with the values entered above.");
        removeButton.setToolTipText(
                "Remove the selected field from the list.");

        JPanel sideButtons = new JPanel(new GridLayout(0, 1, 0, 4));
        sideButtons.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        sideButtons.add(addButton);
        sideButtons.add(replaceButton);
        sideButtons.add(removeButton);

        JPanel listArea = new JPanel(new BorderLayout(4, 0));
        listArea.add(listScroll, BorderLayout.CENTER);
        listArea.add(sideButtons, BorderLayout.EAST);

        add(editorRow,  BorderLayout.NORTH);
        add(listArea,   BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // Event wiring
    // ------------------------------------------------------------------

    private void wireActions() {
        addButton.addActionListener(e -> addField());
        replaceButton.addActionListener(e -> replaceSelected());
        removeButton.addActionListener(e -> removeSelected());
        addImageButton.addActionListener(e -> addImageP18());

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadSelected();
        });
    }

    private void addField() {
        RuleIncludedField field = readEditorField();
        if (field == null) return;
        model.addElement(field);
        list.setSelectedIndex(model.size() - 1);
    }

    private void replaceSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) { addField(); return; }
        RuleIncludedField field = readEditorField();
        if (field == null) return;
        model.set(index, field);
        list.setSelectedIndex(index);
    }

    private void removeSelected() {
        int index = list.getSelectedIndex();
        if (index >= 0) model.remove(index);
    }

    /** Adds the P18 image field only if not already present. */
    private void addImageP18() {
        for (int i = 0; i < model.size(); i++)
            if ("P18".equals(model.get(i).propertyPid())) return;
        model.addElement(RuleIncludedField.imageP18());
        list.setSelectedIndex(model.size() - 1);
    }

    private void loadSelected() {
        RuleIncludedField field = list.getSelectedValue();
        if (field == null) return;
        fieldNameField.setText(field.fieldName());
        pidField.setText(field.propertyPid());
        labelField.setText(field.propertyLabel() == null ? "" : field.propertyLabel());
        kindBox.setSelectedItem(field.kind());
        optionalBox.setSelected(field.optional());
    }

    private RuleIncludedField readEditorField() {
        String pid = RuleNode.cleanPid(pidField.getText());
        if (!pid.matches("P\\d+")) {
            JOptionPane.showMessageDialog(this,
                    "Property PID must look like P18 or P1215.",
                    "Invalid PID", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        String fieldName = fieldNameField.getText().trim();
        if (fieldName.isBlank()) fieldName = toFieldName(labelField.getText(), pid);
        return new RuleIncludedField(
                fieldName, pid, labelField.getText().trim(),
                (RuleIncludedField.FieldKind) kindBox.getSelectedItem(),
                optionalBox.isSelected());
    }

    private static String toFieldName(String label, String pid) {
        String s = label == null || label.isBlank() ? pid : label;
        s = s.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        if (s.startsWith("_")) s = s.substring(1);
        if (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) s = "includedField";
        if (Character.isDigit(s.charAt(0))) s = "v_" + s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
