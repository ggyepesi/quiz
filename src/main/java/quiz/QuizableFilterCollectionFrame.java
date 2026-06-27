package quiz;

import aux.GridBagUtils;
import quiz.ui.QuizablePanel;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class QuizableFilterCollectionFrame extends JFrame {
    private final List<Quizable> allQuizables = new ArrayList<>();
    private final JPanel resultsPanel = new JPanel(new GridBagLayout());
    private final JLabel countLabel = new JLabel(" ");
    private final QuizableFilterConfigEditor filterEditor;
    private final QuizablePanelConfig viewConfig;

    public QuizableFilterCollectionFrame(Collection<? extends Quizable> quizables,
                                         Class<? extends Quizable> cls) {
        this(
                quizables,
                QuizablePanelConfig.allWithMinorFields(cls),
                QuizablePanelConfig.all(cls)
        );
    }

    public QuizableFilterCollectionFrame(Collection<? extends Quizable> quizables,
                                         QuizablePanelConfig filterFieldConfig,
                                         QuizablePanelConfig viewConfig) {
        super("Filter Quizables");

        if (quizables != null) {
            allQuizables.addAll(quizables);
        }

        this.viewConfig = viewConfig == null
                ? QuizablePanelConfig.all(null)
                : viewConfig.copy();

        this.filterEditor = new QuizableFilterConfigEditor(filterFieldConfig);

        buildUi();
        applyFilter();
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));

        resultsPanel.setBorder(BorderFactory.createTitledBorder("Matches"));

        JScrollPane resultsScroll = new JScrollPane(resultsPanel);
        resultsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JScrollPane filterScroll = new JScrollPane(filterEditor);
        filterScroll.setBorder(BorderFactory.createTitledBorder("Filter"));

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                filterScroll,
                resultsScroll
        );
        split.setResizeWeight(0.35);

        JButton applyButton = new JButton("Apply filter");
        applyButton.addActionListener(e -> applyFilter());

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.add(countLabel, BorderLayout.CENTER);
        bottom.add(applyButton, BorderLayout.EAST);

        add(split, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1200, 700);
        setLocationRelativeTo(null);
        setResizable(true);
        setVisible(true);
    }

    private void applyFilter() {
        QuizableFilterConfig filter = filterEditor.getConfig();

        resultsPanel.removeAll();

        int row = 0;
        int matches = 0;

        for (Quizable q : allQuizables) {
            if (q == null) {
                continue;
            }

            if (!filter.isEmpty() && !filter.matches(q)) {
                continue;
            }

            QuizablePanelConfig cfg = viewConfig.copy();

            if (cfg.getCls() == null) {
                cfg.setCls(q.getClass());
            }

            QuizablePanel panel =
                    new QuizablePanel(
                            q,
                            cfg,
                            allQuizables,
                            false
                    );

            resultsPanel.add(panel,
                    GridBagUtils.gbc(
                            0, row++,
                            1.0, 0.0,
                            GridBagConstraints.NORTHWEST,
                            GridBagConstraints.HORIZONTAL,
                            new Insets(4, 4, 8, 4)));

            matches++;
        }

        GridBagConstraints glue =
                GridBagUtils.gbc(
                        0, row,
                        1.0, 1.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.BOTH);

        resultsPanel.add(Box.createGlue(), glue);

        countLabel.setText(matches + " / " + allQuizables.size() + " match");

        resultsPanel.revalidate();
        resultsPanel.repaint();
    }
}