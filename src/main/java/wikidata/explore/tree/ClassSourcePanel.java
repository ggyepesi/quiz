package wikidata.explore.tree;

import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.FieldSourceMapping;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Middle panel when the selected item is the root class.
 *
 * Normal UI answers:
 *   How do we find instances of this class?
 */
public class ClassSourcePanel extends JPanel {

    private GeneratedClassModel clazz;

    private Consumer<Void> afterChange = v -> {};

    private final JLabel titleLabel = new JLabel("Class");

    private final JTextField classNameField = new JTextField(18);
    private final JTextField typeQidField = new JTextField(10);
    private final JLabel typeLabel = new JLabel("(not selected)");

    private final JSpinner limitSpinner =
            new JSpinner(new SpinnerNumberModel(200, 1, 10000, 10));

    private final JCheckBox requireLabelBox =
            new JCheckBox("Require label", true);

    private final JTextField langField =
            new JTextField("en", 4);

    private final JButton applyButton =
            new JButton("Apply class source");

    private final JLabel summaryLabel =
            new JLabel(" ");

    public ClassSourcePanel() {
        super(new BorderLayout(4, 4));
        buildUi();
    }

    public void afterChange(Consumer<Void> afterChange) {
        this.afterChange = afterChange == null ? v -> {} : afterChange;
    }

    public void edit(GeneratedClassModel clazz) {
        this.clazz = clazz;

        if (clazz == null) {
            clear();
            return;
        }

        FieldSourceMapping m = clazz.instanceMapping();

        titleLabel.setText("Class: " + clazz.className());

        classNameField.setText(clazz.className());
        typeQidField.setText(m.sourceQid());
        typeLabel.setText(m.displaySource());

        limitSpinner.setValue(Math.max(1, m.limit()));
        requireLabelBox.setSelected(m.requireLabel());
        langField.setText(m.labelLanguage());

        updateSummary();
    }

    /**
     * Called from QID finder/WikiProject later.
     */
    public void useSourceQid(String qid, String label) {
        if (clazz == null) {
            return;
        }

        clazz.instanceMapping().sourceQid(qid);
        clazz.instanceMapping().sourceLabel(label);
        typeQidField.setText(clazz.instanceMapping().sourceQid());
        typeLabel.setText(clazz.instanceMapping().displaySource());
        updateSummary();
        afterChange.accept(null);
    }

    public RuleNode toRuleNode() {
        return clazz == null ? null : RuleTreeCompiler.compileClass(clazz);
    }

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        addWide(form, c, y++, titleLabel);

        JLabel question = new JLabel("How do we find instances of this class?");
        question.setFont(question.getFont().deriveFont(Font.ITALIC));
        addWide(form, c, y++, question);

        addRow(form, c, y++, "Class name:", classNameField);

        JPanel typeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        typeRow.add(typeQidField);
        typeRow.add(typeLabel);
        addRow(form, c, y++, "Wikidata type/class:", typeRow);

        JLabel relation = new JLabel("Relation: item is instance of selected type (P31)");
        addWide(form, c, y++, relation);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        options.add(new JLabel("Limit:"));
        options.add(limitSpinner);
        options.add(requireLabelBox);
        options.add(new JLabel("lang:"));
        options.add(langField);
        addWide(form, c, y++, options);

        addWide(form, c, y++, summaryLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(applyButton);
        addWide(form, c, y++, buttons);

        add(scroll, BorderLayout.CENTER);

        applyButton.addActionListener(e -> apply());
    }

    private void apply() {
        if (clazz == null) {
            return;
        }

        clazz.className(classNameField.getText());

        FieldSourceMapping m = clazz.instanceMapping();
        m.sourceQid(typeQidField.getText());
        m.propertyPid("P31");
        m.propertyLabel("instance of");
        m.direction(RuleDirection.ITEM_TO_ROOT);
        m.limit(((Number) limitSpinner.getValue()).intValue());
        m.requireLabel(requireLabelBox.isSelected());
        m.labelLanguage(langField.getText());

        titleLabel.setText("Class: " + clazz.className());
        typeLabel.setText(m.displaySource());

        updateSummary();
        afterChange.accept(null);
    }

    private void updateSummary() {
        if (clazz == null) {
            summaryLabel.setText(" ");
            return;
        }

        summaryLabel.setText(
                "Current source: items with instance of "
                        + clazz.instanceMapping().displaySource());
    }

    private void clear() {
        titleLabel.setText("Class");
        classNameField.setText("");
        typeQidField.setText("");
        typeLabel.setText("(not selected)");
        summaryLabel.setText(" ");
    }

    private static void addRow(
            JPanel form,
            GridBagConstraints c,
            int y,
            String label,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1;
        form.add(comp, c);
    }

    private static void addWide(
            JPanel form,
            GridBagConstraints c,
            int y,
            JComponent comp) {

        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(comp, c);
        c.gridwidth = 1;
    }
}
