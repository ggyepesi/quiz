package wikidata.explore.ui;

import aux.GridBagUtils;
import wikidata.WorkbenchController;
import wikidata.explore.WorkbenchState;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;
import java.awt.*;

public class RuleBuilderPanel extends JPanel {

    private static final Insets DEFAULT_INSETS =
            new Insets(3, 3, 3, 3);

    public RuleBuilderPanel(
            WorkbenchState state,
            WorkbenchController controller) {

        super(new BorderLayout(6, 6));

        setBorder(BorderFactory.createTitledBorder(
                "3. Build draft rule"));

        GroupedTripleTree tripleTree = new GroupedTripleTree();
        controller.setTripleTree(tripleTree);

        JScrollPane typeScroll = new JScrollPane(state.typesList);
        typeScroll.setBorder(BorderFactory.createTitledBorder(
                "B. Target type(s) — select one or more"));

        tripleTree.setBorder(BorderFactory.createTitledBorder(
                "A. Relation/property groups — select group; double-click value to follow"));

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                tripleTree,
                typeScroll);
        split.setResizeWeight(0.65);
        split.setPreferredSize(new Dimension(900, 520));

        ListSelectionListener refresh = e -> {
            if (!e.getValueIsAdjusting()) {
                controller.refreshDraftPreview();
            }
        };
        state.typesList.addListSelectionListener(refresh);

        add(split, BorderLayout.CENTER);
        add(buildOptionsPanel(state, controller), BorderLayout.SOUTH);
    }

    private JPanel buildOptionsPanel(
            WorkbenchState state,
            WorkbenchController controller) {

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder("Draft rule options"));

        int r = 0;
        addRow(p, r++, "Rule name", state.ruleNameField);
        addRow(p, r++, "Item variable", state.itemVarField);
        addRow(p, r++, "Direction", state.directionBox);
        addRow(p, r++, "Type matching", state.subclassClosureBox);
        addRow(p, r++, "Query limit", state.limitField);
        addRow(p, r++, "Manual type QID", state.manualTypeQidField);
        addRow(p, r++, "Manual type label", state.manualTypeLabelField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton addManualType = new JButton("Add manual type");
        JButton preview = new JButton("Preview draft rule");
        JButton test = new JButton("Test draft rule");
        JButton save = new JButton("Save draft rule");

        addManualType.addActionListener(e -> controller.addManualType());
        preview.addActionListener(e -> controller.previewDraftRule());
        test.addActionListener(e -> controller.testDraftRule());
        save.addActionListener(e -> controller.saveDraftRule());

        buttons.add(addManualType);
        buttons.add(preview);
        buttons.add(test);
        buttons.add(save);

        p.add(buttons, GridBagUtils.gbc(
                0, r, 2, 1,
                GridBagConstraints.WEST,
                GridBagConstraints.NONE,
                DEFAULT_INSETS));

        return p;
    }

    private void addRow(JPanel p, int row, String label, Component field) {
        p.add(new JLabel(label), GridBagUtils.gbc(
                0, row, 0, 0,
                GridBagConstraints.WEST,
                GridBagConstraints.NONE,
                DEFAULT_INSETS));

        p.add(field, GridBagUtils.gbc(
                1, row, 1, 0,
                GridBagConstraints.CENTER,
                GridBagConstraints.HORIZONTAL,
                DEFAULT_INSETS));
    }
}