package wikidata.explore;

import wikidata.explore.ui.OutputPanel;

import javax.swing.*;
import java.awt.*;

public class LogFrame extends JFrame {

    public LogFrame(OutputPanel output) {
        super("SPARQL Log");

        setLayout(new BorderLayout());
        add(output, BorderLayout.CENTER);

        setSize(900, 700);
        setLocationByPlatform(true);
    }
}