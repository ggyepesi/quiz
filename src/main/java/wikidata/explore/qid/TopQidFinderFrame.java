package wikidata.explore.qid;

import wikidata.WikidataSparqlClient;

import javax.swing.*;
import java.awt.*;

public class TopQidFinderFrame extends JFrame {

    private final TopQidFinderPanel finderPanel;

    public TopQidFinderFrame(WikidataSparqlClient client) {
        super("Top QID Finder");

        SparqlLogPanel logPanel = new SparqlLogPanel();
        finderPanel =
                new TopQidFinderPanel(client, logPanel);

        setLayout(new BorderLayout());

        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        finderPanel,
                        titled("SPARQL Log", logPanel));

        split.setResizeWeight(0.72);

        add(split, BorderLayout.CENTER);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 850);
        setLocationByPlatform(true);
    }

    public TopQidFinderPanel finderPanel() {
        return finderPanel;
    }

    private static JComponent titled(String title, JComponent component) {
        JPanel panel =
                new JPanel(new BorderLayout());

        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(component, BorderLayout.CENTER);

        return panel;
    }
}
