package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;

/**
 * A labelled configuration row that can be hidden as a unit.
 *
 * <p>Rows were built by handing a string literal to the layout, so nothing held the
 * label and hiding a control left its label beside empty space. That is why this panel
 * greys twenty-two controls and hides three: greying was the only option the helper
 * left, not a decision that an inapplicable control should still be read.
 *
 * <p>Hiding is for a row that cannot apply to what is being configured. A control that
 * COULD apply but is unavailable here stays visible and greyed, because seeing it
 * teaches what the field might become — see the qualifier source, which is deliberately
 * shown on a class that cannot use it.
 */
final class FormRow {

    private final JLabel label;
    private final JComponent field;

    private FormRow(JLabel label, JComponent field) {
        this.label = label;
        this.field = field;
    }

    /** Adds a labelled row and keeps hold of both halves. */
    static FormRow add(JPanel form, GridBagConstraints template, int gridy,
                       String text, JComponent field) {
        JLabel label = new JLabel(text);
        GridBagUtils.labeledRow(form, template, gridy, label, field);
        return new FormRow(label, field);
    }

    /** Shows or hides the label and its control together. */
    void applicable(boolean applicable) {
        label.setVisible(applicable);
        field.setVisible(applicable);
    }

    boolean applicable() {
        return field.isVisible();
    }

    String label() {
        return label.getText();
    }
}
