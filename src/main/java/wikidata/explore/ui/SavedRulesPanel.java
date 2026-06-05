package wikidata.explore.ui;

import wikidata.WorkbenchController;
import wikidata.explore.WorkbenchState;

import javax.swing.*;
import java.awt.*;

public class SavedRulesPanel extends JPanel {

    public SavedRulesPanel(
            WorkbenchState state,
            WorkbenchController controller) {

        super(new BorderLayout(6, 6));

        setBorder(BorderFactory.createTitledBorder("4. Saved rules"));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton test = new JButton("Test saved rule");
        JButton remove = new JButton("Remove saved rule");

        test.addActionListener(e ->
                                       controller.testSelectedSavedRule());

        remove.addActionListener(e ->
                                         controller.removeSelectedSavedRule());
        buttons.add(test);
        buttons.add(remove);

        add(new JScrollPane(state.specsList), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }
}