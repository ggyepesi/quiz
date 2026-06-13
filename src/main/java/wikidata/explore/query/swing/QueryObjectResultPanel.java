package wikidata.explore.query.swing;

import quiz.Quizable;
import quiz.ui.QuizablePanelView;
import quiz.ui.QuizableSearchPanel;
import wikidata.explore.query.core.QueryResultSink;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.*;
import java.awt.*;

public class QueryObjectResultPanel
        extends JPanel
        implements QueryResultSink<ObjectQueryResult> {

    public enum ViewMode {
        SEARCH_PANEL,
        TYPE_PANEL_DEMO
    }

    private ViewMode viewMode = ViewMode.SEARCH_PANEL;
    private final JPanel holder = new JPanel(new BorderLayout());

    public QueryObjectResultPanel() {
        super(new BorderLayout());
        add(holder, BorderLayout.CENTER);
    }

    public void viewMode(ViewMode viewMode) {
        this.viewMode =
                viewMode == null ? ViewMode.SEARCH_PANEL : viewMode;
    }

    public void clear() {
        holder.removeAll();
        holder.revalidate();
        holder.repaint();
    }

    @Override
    public void accept(ObjectQueryResult result) {
        SwingUtilities.invokeLater(() -> {
            clear();

            if (result == null
                    || result.objects() == null
                    || result.objects().isEmpty()) {
                holder.add(new JLabel("No objects."), BorderLayout.CENTER);
                refresh();
                return;
            }

            if (viewMode == ViewMode.TYPE_PANEL_DEMO) {
                holder.add(
                        new JLabel(
                                "<html>"
                                        + "<b>Type-panel demo renderer not integrated yet.</b><br>"
                                        + "Keep TypePanelDemo as the prototype for "
                                        + "per-type panels and cross-reference navigation.<br>"
                                        + "Future implementation should reuse QuizablePanel "
                                        + "instead of hand-built JLabel rows."
                                        + "</html>"),
                        BorderLayout.NORTH);

                holder.add(searchPanelView(result), BorderLayout.CENTER);
            } else {
                holder.add(searchPanelView(result), BorderLayout.CENTER);
            }

            refresh();
        });
    }

    private JComponent searchPanelView(ObjectQueryResult result) {
        QuizablePanelView view = new QuizablePanelView();

        for (Quizable q : result.objects()) {
            view.addQuizable(q);
        }

        view.createCardsPanel(1);

        JPanel wrapped = new JPanel(new BorderLayout());

        Quizable first = result.objects().getFirst();

        QuizableSearchPanel searchPanel =
                new QuizableSearchPanel(first.getClass());

        searchPanel.setTarget(
                view.getCardsPanel(),
                view.getCardsScrollPane());
        view.addTargetListener(searchPanel);

        wrapped.add(searchPanel, BorderLayout.NORTH);
        wrapped.add(view.getCardsScrollPane(), BorderLayout.CENTER);

        return wrapped;
    }

    private void refresh() {
        holder.revalidate();
        holder.repaint();
    }
}