package wikidata.explore.ui;

import aux.GridBagUtils;
import wikidata.WorkbenchController;
import wikidata.explore.WorkbenchState;

import javax.swing.*;
import java.awt.*;

public class RuleOptionsPanel extends JPanel {

    private static final Insets DEFAULT_INSETS =
            new Insets(3, 3, 3, 3);

    public RuleOptionsPanel(
            WorkbenchState state,
            WorkbenchController controller) {

        super(new BorderLayout());

        JPanel p = new JPanel(new GridBagLayout());

        int r = 0;

        addRow(p, r++, "Rule name", state.ruleNameField);
        addRow(p, r++, "Item variable", state.itemVarField);
        addRow(p, r++, "Direction", state.directionBox);
        addRow(p, r++, "Subclass matching", state.subclassClosureBox);

        addRow(p, r++, "Manual type QID",
               state.manualTypeQidField);

        addRow(p, r++, "Manual type label",
               state.manualTypeLabelField);

        JPanel buttons =
                new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton addManualType =
                new JButton("Add manual type");

        JButton preview =
                new JButton("Preview");

        JButton test =
                new JButton("Test");

        JButton save =
                new JButton("Save");

        addManualType.addActionListener(
                e -> controller.addManualType());

        preview.addActionListener(
                e -> controller.previewDraftRule());

        test.addActionListener(
                e -> controller.testDraftRule());

        save.addActionListener(
                e -> controller.saveDraftRule());

        buttons.add(addManualType);
        buttons.add(preview);
        buttons.add(test);
        buttons.add(save);

        p.add(buttons,
              GridBagUtils.gbc(
                      0, r,
                      2, 1,
                      GridBagConstraints.WEST,
                      GridBagConstraints.NONE,
                      DEFAULT_INSETS));

        add(p, BorderLayout.CENTER);
    }

    private void addRow(
            JPanel p,
            int row,
            String label,
            Component field) {

        p.add(new JLabel(label),
              GridBagUtils.gbc(
                      0, row,
                      0, 0,
                      GridBagConstraints.WEST,
                      GridBagConstraints.NONE,
                      DEFAULT_INSETS));

        p.add(field,
              GridBagUtils.gbc(
                      1, row,
                      1, 0,
                      GridBagConstraints.CENTER,
                      GridBagConstraints.HORIZONTAL,
                      DEFAULT_INSETS));
    }
}