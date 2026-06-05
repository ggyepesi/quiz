package wikidata.explore.tree;

import quiz.Quizable;
import quiz.QuizablePanelConfig;
import quiz.ui.QuizablePanel;
import quiz.ui.QuizableRenderContext;
import quiz.ui.QuizableSearchPanel;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class CachedPropertyQuizablePanel extends JPanel {

    private final JPanel cardsPanel =
            new JPanel(new GridBagLayout());

    private final JScrollPane cardsScrollPane =
            new JScrollPane(cardsPanel);

    private final QuizableSearchPanel searchPanel =
            new QuizableSearchPanel(WikidataPropertyQuizable.class);

    private final JLabel statusLabel =
            new JLabel(" ");

    private Consumer<WikidataPropertyQuizable> propertySelected =
            p -> {};

    private final List<WikidataPropertyQuizable> properties =
            new ArrayList<>();

    public CachedPropertyQuizablePanel() {
        super(new BorderLayout(6, 6));
        cardsScrollPane.getVerticalScrollBar().setUnitIncrement(18);
        cardsScrollPane.setPreferredSize(new Dimension(460, 420));

        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        searchPanel,
                        cardsScrollPane);

        split.setResizeWeight(0.28);

        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
        loadProperties();
    }

    public void onPropertySelected(
            Consumer<WikidataPropertyQuizable> propertySelected) {

        this.propertySelected =
                propertySelected == null ? p -> {} : propertySelected;
    }

    private void loadProperties() {
        properties.clear();
        try {
            WikidataPropertyStore store = new WikidataPropertyStore();

            for (WikidataProperty p : store.read()) {
                properties.add(new WikidataPropertyQuizable(p));
            }

            rebuildCards();
            statusLabel.setText(
                    "Loaded cached properties: " + properties.size());

        } catch (Exception ex) {
            statusLabel.setText(
                    "Could not load property cache: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void rebuildCards() {
        cardsPanel.removeAll();

        List<Quizable> quizables = new ArrayList<>(properties);
        QuizableRenderContext context =
                new QuizableRenderContext(quizables);

        QuizablePanelConfig config =
                QuizablePanelConfig.of(WikidataPropertyQuizable.class);

        config.setAllFields(false);
        config.setThumb(false);
        config.setAddListener(false);
        config.addField("name", QuizablePanelConfig.leaf());
        config.addField("pid", QuizablePanelConfig.leaf());
        config.addField("description", QuizablePanelConfig.leaf());
        context.putClassConfig(WikidataPropertyQuizable.class, config);

        int row = 0;

        for (WikidataPropertyQuizable property : properties) {
            QuizablePanel panel =
                    new QuizablePanel(property, config.copy(), context, false);

            context.registerTopLevel(property, panel);

            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(3, 3, 3, 3),
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY)));

            // Single listener on the panel itself — not recursed into children.
            // Using mousePressed so it fires before any child component consumes
            // the event, and consuming the event prevents duplicate firing.
            panel.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    propertySelected.accept(property);
                    e.consume();
                }
            });

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx   = 0;
            gbc.gridy   = row++;
            gbc.weightx = 1.0;
            gbc.fill    = GridBagConstraints.HORIZONTAL;
            gbc.anchor  = GridBagConstraints.NORTHWEST;
            gbc.insets  = new Insets(3, 3, 3, 3);
            cardsPanel.add(panel, gbc);
        }

        GridBagConstraints glue = new GridBagConstraints();
        glue.gridx   = 0;
        glue.gridy   = row;
        glue.weightx = 1.0;
        glue.weighty = 1.0;
        glue.fill    = GridBagConstraints.BOTH;
        cardsPanel.add(Box.createGlue(), glue);

        cardsPanel.revalidate();
        cardsPanel.repaint();
        searchPanel.setTarget(cardsPanel, cardsScrollPane);
    }
}
