package quiz;

import objectview.Viewable;
import objectview.utils.swing.GridBagUtils;
import objectview.render.Card;
import objectview.viewconfig.ViewConfig;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ViewableFilterCollectionFrame extends JFrame {
    private final List<Viewable> allViewables = new ArrayList<>();
    private final JPanel resultsPanel = new JPanel(new GridBagLayout());
    private final JLabel countLabel = new JLabel(" ");
    private final ViewableFilterConfigEditor filterEditor;
    private final ViewConfig viewConfig;

    public ViewableFilterCollectionFrame(Collection<? extends Viewable> viewables,
                                         Class<? extends Viewable> cls) {
        this(
                viewables,
                ViewConfig.allWithMinorFields(cls),
                ViewConfig.all(cls)
        );
    }

    public ViewableFilterCollectionFrame(Collection<? extends Viewable> viewables,
                                         ViewConfig filterFieldConfig,
                                         ViewConfig viewConfig) {
        super("Filter Viewables");

        if (viewables != null) {
            allViewables.addAll(viewables);
        }

        this.viewConfig = viewConfig == null
                ? ViewConfig.all(null)
                : viewConfig.copy();

        this.filterEditor = new ViewableFilterConfigEditor(filterFieldConfig);

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
        ViewableFilterConfig filter = filterEditor.getConfig();

        resultsPanel.removeAll();

        int row = 0;
        int matches = 0;

        for (Viewable q : allViewables) {
            if (q == null) {
                continue;
            }

            if (!filter.isEmpty() && !filter.matches(q)) {
                continue;
            }

            ViewConfig cfg = viewConfig.copy();

            if (cfg.getCls() == null) {
                cfg.setCls(q.getClass());
            }

            Card panel =
                    new Card(
                            q,
                            cfg,
                            allViewables,
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

        countLabel.setText(matches + " / " + allViewables.size() + " match");

        resultsPanel.revalidate();
        resultsPanel.repaint();
    }
}
