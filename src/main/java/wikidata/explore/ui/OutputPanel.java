package wikidata.explore.ui;

import javax.swing.*;
import java.awt.*;

public class OutputPanel extends JPanel {

    private final JTextArea ruleArea = new JTextArea(10, 35);
    private final JTextArea logArea = new JTextArea(20, 80);

    private final JScrollPane ruleScroll;
    private final JScrollPane logScroll;

    private final JLabel currentTaskLabel = new JLabel("Idle");
    private final JButton cancelButton = new JButton("Cancel");

    public OutputPanel() {
        super(new BorderLayout(6, 6));

        ruleArea.setEditable(false);
        logArea.setEditable(false);

        ruleArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        ruleScroll = new JScrollPane(ruleArea);
        ruleScroll.setBorder(BorderFactory.createTitledBorder(
                "Draft rule explanation"));

        logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                "Log / SPARQL"));

        cancelButton.setEnabled(false);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT));
        status.add(new JLabel("Current task:"));
        status.add(currentTaskLabel);
        status.add(cancelButton);

        add(status, BorderLayout.NORTH);
        add(logScroll, BorderLayout.CENTER);
    }

    public JScrollPane getRuleInfoScrollPane() {
        return ruleScroll;
    }

    public JButton getCancelButton() {
        return cancelButton;
    }

    public void setCurrentTask(String text) {
        currentTaskLabel.setText(
                text == null || text.isBlank() ? "Idle" : text);
    }

    public void setRuleInfo(String text) {
        ruleArea.setText(text == null ? "" : text);
        ruleArea.setCaretPosition(0);
    }

    public void append(String text) {
        logArea.append(text);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public void appendSparql(String sparql) {
        append("\nSPARQL\n");
        append("------\n");
        append(sparql);
        append("\n");
    }
}