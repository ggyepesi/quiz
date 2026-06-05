package wikidata.explore.ui;

import aux.GridBagUtils;
import wikidata.WorkbenchController;
import wikidata.explore.WorkbenchState;

import javax.swing.*;
import java.awt.*;

public class RootPanel extends JPanel {

    private static final Insets DEFAULT_INSETS =
            new Insets(3, 3, 3, 3);

    public RootPanel(
            WorkbenchState state,
            WorkbenchController controller) {

        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder("1. Root entity"));

        int r = 0;
        addRow(r++, "Root name", state.rootNameField);
        addRow(r++, "Root QID", state.rootQidField);

        JButton back = new JButton("Back to previous root");
        back.addActionListener(e -> controller.goBack());

        add(back, GridBagUtils.gbc(
                0, r, 2, 1,
                GridBagConstraints.WEST,
                GridBagConstraints.NONE,
                DEFAULT_INSETS));
    }

    private void addRow(int row, String label, Component field) {
        add(new JLabel(label), GridBagUtils.gbc(
                0, row, 0, 0,
                GridBagConstraints.WEST,
                GridBagConstraints.NONE,
                DEFAULT_INSETS));

        add(field, GridBagUtils.gbc(
                1, row, 1, 0,
                GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL,
                DEFAULT_INSETS));
    }
}