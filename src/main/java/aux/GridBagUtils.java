package aux;

import java.awt.GridBagConstraints;
import java.awt.Insets;

public final class GridBagUtils {
    private GridBagUtils() {}

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