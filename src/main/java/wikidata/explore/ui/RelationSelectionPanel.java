package wikidata.explore.ui;

import wikidata.WorkbenchController;
import wikidata.explore.WorkbenchState;

import javax.swing.*;
import javax.swing.event.ListSelectionListener;
import java.awt.*;

public class RelationSelectionPanel extends JPanel {

    public RelationSelectionPanel(
            WorkbenchState state,
            WorkbenchController controller) {

        super(new BorderLayout(6, 6));

        GroupedTripleTree tripleTree = new GroupedTripleTree();
        controller.setTripleTree(tripleTree);

        JScrollPane typeScroll = new JScrollPane(state.typesList);

        typeScroll.setBorder(
                BorderFactory.createTitledBorder(
                        "Target type(s)"));

        tripleTree.setBorder(
                BorderFactory.createTitledBorder(
                        "Relations"));

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                tripleTree,
                typeScroll);

        split.setResizeWeight(0.75);

        ListSelectionListener refresh = e -> {
            if (!e.getValueIsAdjusting()) {
                controller.refreshDraftPreview();
            }
        };

        state.typesList.addListSelectionListener(refresh);

        add(split, BorderLayout.CENTER);
    }
}