package aux;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.Insets;

public final class GridBagUtils {
    private GridBagUtils() {}

    /** The default padding for a form row's cells. */
    private static final Insets ROW_INSETS = new Insets(4, 4, 4, 4);

    /**
     * Constraints for a full-width cell spanning {@code gridwidth} columns, with
     * the standard form padding, left-aligned and stretched horizontally. The
     * common label/field-form default — the shorthand the workbench panels reuse.
     */
    public static GridBagConstraints gbc(int gridx, int gridy, int gridwidth) {
        return gbc(
                gridx,
                gridy,
                gridwidth,
                1.0,
                0.0,
                GridBagConstraints.WEST,
                GridBagConstraints.HORIZONTAL,
                ROW_INSETS);
    }

    /**
     * Adds a two-cell form row: a label in column 0 (no horizontal weight) and a
     * field in column 1 (takes the slack). The row inherits the {@code template}'s
     * insets/anchor/fill, so callers control padding and alignment per form. Each
     * cell gets its own cloned constraints, so the template is left untouched.
     */
    public static void labeledRow(
            JPanel form,
            GridBagConstraints template,
            int gridy,
            String label,
            JComponent field) {
        labeledRow(form, template, gridy, new JLabel(label), field);
    }

    /** {@link #labeledRow(JPanel, GridBagConstraints, int, String, JComponent)}
     *  with a pre-built label (e.g. one whose text changes at runtime). */
    public static void labeledRow(
            JPanel form,
            GridBagConstraints template,
            int gridy,
            JLabel label,
            JComponent field) {

        GridBagConstraints labelCell = (GridBagConstraints) template.clone();
        labelCell.gridx = 0;
        labelCell.gridy = gridy;
        labelCell.gridwidth = 1;
        labelCell.weightx = 0;
        form.add(label, labelCell);

        GridBagConstraints fieldCell = (GridBagConstraints) template.clone();
        fieldCell.gridx = 1;
        fieldCell.gridy = gridy;
        fieldCell.gridwidth = 1;
        fieldCell.weightx = 1.0;
        form.add(field, fieldCell);
    }

    /** Adds a component spanning both form columns, with the standard row padding
     *  and horizontal stretch (see {@link #gbc(int, int, int)}). */
    public static void wideRow(JPanel form, int gridy, JComponent component) {
        form.add(component, gbc(0, gridy, 2));
    }

    public static GridBagConstraints gbc(
            int gridx,
            int gridy,
            double weightx,
            double weighty,
            int anchor,
            int fill) {

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = gridx;
        gbc.gridy = gridy;
        gbc.weightx = weightx;
        gbc.weighty = weighty;
        gbc.anchor = anchor;
        gbc.fill = fill;
        return gbc;
    }

    public static GridBagConstraints gbc(
            int gridx,
            int gridy,
            double weightx,
            double weighty,
            int anchor,
            int fill,
            Insets insets) {

        GridBagConstraints gbc = gbc(gridx, gridy, weightx, weighty, anchor, fill);
        gbc.insets = insets;
        return gbc;
    }

    public static GridBagConstraints gbc(
            int gridx,
            int gridy,
            int gridwidth,
            double weightx,
            double weighty,
            int anchor,
            int fill,
            Insets insets) {

        GridBagConstraints gbc = gbc(
                gridx,
                gridy,
                weightx,
                weighty,
                anchor,
                fill,
                insets);

        gbc.gridwidth = gridwidth;

        return gbc;
    }

    public static GridBagConstraints gbc(
            int gridx,
            int gridy,
            int gridwidth,
            int gridheight,
            double weightx,
            double weighty,
            int anchor,
            int fill,
            Insets insets) {

        GridBagConstraints gbc = gbc(gridx, gridy, gridwidth, weightx, weighty,
                                     anchor, fill, insets);
        gbc.gridheight = gridheight;
        return gbc;
    }
}