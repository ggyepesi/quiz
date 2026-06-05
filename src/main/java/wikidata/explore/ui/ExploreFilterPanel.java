package wikidata.explore.ui;

import wikidata.explore.WorkbenchState;

import javax.swing.*;
import java.awt.*;

public class ExploreFilterPanel extends JPanel {

    public ExploreFilterPanel(WorkbenchState state) {
        super(new FlowLayout(FlowLayout.LEFT));

        setBorder(BorderFactory.createTitledBorder(
                "Filters"));

        add(state.requireLabelBox);

        add(new JLabel("Min length km"));
        add(state.minLengthKmField);

        add(new JLabel("Min area km²"));
        add(state.minAreaKm2Field);

        add(new JLabel("Limit"));
        add(state.limitField);
    }
}