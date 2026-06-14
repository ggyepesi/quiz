package wikidata.explore.query.swing;

import quiz.ui.QuizablePanelView;
import quiz.ui.QuizableSearchPanel;
import wikidata.explore.query.core.QueryStatus;
import wikidata.explore.query.log.LogKind;
import wikidata.explore.query.log.LogListener;
import wikidata.explore.query.log.LogNode;
import wikidata.explore.query.log.WorkflowRecorder;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class WorkflowLogWindow implements LogListener {

    private final List<LogNode> workflows =
            new ArrayList<>();

    private QuizablePanelView view;
    private JFrame frame;

    @Override
    public void logChanged(
            LogNode root,
            boolean added) {

        SwingUtilities.invokeLater(() -> {
            if (added && !workflows.contains(root)) {
                workflows.add(root);
            }

            if (view != null) {
                view.upsertQuizable(root);
            }
        });
    }

    public void show(Component owner) {
        if (frame != null) {
            frame.setVisible(true);
            frame.toFront();
            return;
        }

        QuizablePanelView v =
                new QuizablePanelView();

        for (LogNode workflow : workflows) {
            v.addQuizable(workflow);
        }

        v.createCardsPanel(1);

        QuizableSearchPanel search =
                new QuizableSearchPanel(LogNode.class);

        search.setTarget(
                v.getCardsPanel(),
                v.getCardsScrollPane());

        v.addTargetListener(search);

        JFrame f =
                new JFrame("Query Logs");

        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.setLayout(new BorderLayout(6, 6));
        f.add(search, BorderLayout.NORTH);
        f.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        f.setSize(1000, 700);
        f.setLocationRelativeTo(owner);

        frame = f;
        view = v;

        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                frame = null;
                view = null;
            }
        });

        f.setVisible(true);
    }

    public void info(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        WorkflowRecorder recorder =
                new WorkflowRecorder(
                        new LogNode(LogKind.MESSAGE, "Log message"));

        recorder.setListener(this);
        recorder.added();
        recorder.start();
        recorder.message(text);
        recorder.finish(QueryStatus.OK, null, null);
    }
}
