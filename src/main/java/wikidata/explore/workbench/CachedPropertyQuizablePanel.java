package wikidata.explore.workbench;

import aux.GridBagUtils;
import objectview.ViewablePanel;
import objectview.ViewableSearchPanel;
import objectview.viewconfig.ViewablePanelConfig;
import quiz.Quizable;
import objectview.ViewableRenderContext;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class CachedPropertyQuizablePanel extends JPanel {

    private final JPanel cardsPanel =
            new JPanel(new GridBagLayout());

    private final JScrollPane cardsScrollPane =
            new JScrollPane(cardsPanel);

    private final ViewableSearchPanel searchPanel =
            new ViewableSearchPanel(WikidataPropertyQuizable.class);

    private final JLabel statusLabel =
            new JLabel(" ");

    private Consumer<WikidataPropertyQuizable> propertySelected =
            p -> {};

    private final List<WikidataPropertyQuizable> properties =
            new ArrayList<>();

    private final Map<String, WikidataProperty> cache =
            new LinkedHashMap<>();

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

    public Map<String, WikidataProperty> propertyCache() {
        return cache;
    }

    private void loadProperties() {
        properties.clear();
        cache.clear();
        try {
            WikidataPropertyStore store = new WikidataPropertyStore();

            for (WikidataProperty p : store.read()) {
                properties.add(new WikidataPropertyQuizable(p));
                cache.put(p.pid(), p);
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
        ViewableRenderContext context =
                new ViewableRenderContext(quizables);

        ViewablePanelConfig config =
                ViewablePanelConfig.of(WikidataPropertyQuizable.class);

        config.setAllFields(false);
        config.setThumb(false);
        config.setAddListener(false);
        config.addField("name", ViewablePanelConfig.leaf());
        config.addField("pid", ViewablePanelConfig.leaf());
        config.addField("description", ViewablePanelConfig.leaf());
        context.putClassConfig(WikidataPropertyQuizable.class, config);

        int row = 0;

        for (WikidataPropertyQuizable property : properties) {
            ViewablePanel panel =
                    new ViewablePanel(property, config.copy(), context, false);

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

            GridBagUtils.stackedCard(cardsPanel, row++, panel);
        }

        GridBagUtils.verticalGlue(cardsPanel, row);

        cardsPanel.revalidate();
        cardsPanel.repaint();
        searchPanel.setTarget(cardsPanel, cardsScrollPane);
    }
}
