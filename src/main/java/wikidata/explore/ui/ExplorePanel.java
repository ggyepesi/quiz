package wikidata.explore.ui;

import wikidata.WorkbenchController;

import javax.swing.*;
import java.awt.*;

public class ExplorePanel extends JPanel {

    public ExplorePanel(WorkbenchController controller) {
        super(new FlowLayout(FlowLayout.LEFT));

        setBorder(BorderFactory.createTitledBorder("2. Explore Wikidata"));

        JButton outgoing = new JButton("Load outgoing relations");
        JButton incoming = new JButton("Load incoming relations");
        JButton loadSelectedValueTypesButton =
                new JButton("Types of selected value");

        JButton types = new JButton("Load target types");
        JButton cancel = new JButton("Cancel query");

        outgoing.addActionListener(e -> {
            try {
                controller.loadOutgoingTriples();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        incoming.addActionListener(e -> {
            try {
                controller.loadIncomingTriples();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        loadSelectedValueTypesButton.addActionListener(
                e -> controller.loadTypesOfSelectedRelationValue());
        types.addActionListener(e -> {
            try {
                controller.loadTypes();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        cancel.addActionListener(e -> controller.cancelCurrentQuery());

        add(outgoing);
        add(incoming);
        add(loadSelectedValueTypesButton);
        add(types);
        add(cancel);
    }
}