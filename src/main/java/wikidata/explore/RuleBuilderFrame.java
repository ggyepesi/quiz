package wikidata.explore;

import wikidata.WorkbenchController;
import wikidata.explore.ui.OutputPanel;
import wikidata.explore.ui.RuleOptionsPanel;
import wikidata.explore.ui.SavedRulesPanel;

import javax.swing.*;
import java.awt.*;

public class RuleBuilderFrame extends JFrame {

    public RuleBuilderFrame(
            WorkbenchState state,
            WorkbenchController controller,
            OutputPanel output) {

        super("Rule Builder");

        setLayout(new BorderLayout(6, 6));

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                new RuleOptionsPanel(state, controller),
                new SavedRulesPanel(state, controller));

        split.setResizeWeight(0.5);

        add(split, BorderLayout.CENTER);
        add(output.getRuleInfoScrollPane(),
            BorderLayout.SOUTH);

        setSize(700, 700);
        setLocationByPlatform(true);
    }
}