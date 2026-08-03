package wikidata.explore.workbench;

import objectview.utils.swing.GridBagUtils;
import objectview.render.Card;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import objectview.viewconfig.ViewConfig;
import objectview.Viewable;
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

public class CachedPropertyViewablePanel extends JPanel {

    private final JPanel cardsPanel =
            new JPanel(new GridBagLayout());

    private final JScrollPane cardsScrollPane =
            new JScrollPane(cardsPanel);

    private final SearchPanel searchPanel =
            new SearchPanel(WikidataPropertyViewable.class);

    private final JLabel statusLabel =
            new JLabel(" ");

    private Consumer<WikidataPropertyViewable> propertySelected =
            p -> {};

    private final List<WikidataPropertyViewable> properties =
            new ArrayList<>();

    private final Map<String, WikidataProperty> cache =
            new LinkedHashMap<>();

    public CachedPropertyViewablePanel() {
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
            Consumer<WikidataPropertyViewable> propertySelected) {

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
                properties.add(new WikidataPropertyViewable(p));
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

        List<Viewable> viewables = new ArrayList<>(properties);
        RenderContext context =
                new RenderContext(viewables);

        ViewConfig config =
                ViewConfig.of(WikidataPropertyViewable.class);

        config.setAllFields(false);
        config.setThumb(false);
        config.setAddListener(false);
        config.addField(objectview.field.ViewableContractFieldSet.DISPLAY_KEY,
                ViewConfig.leaf());
        config.addField("pid", ViewConfig.leaf());
        config.addField("description", ViewConfig.leaf());
        context.putClassConfig(WikidataPropertyViewable.class, config);

        int row = 0;

        for (WikidataPropertyViewable property : properties) {
            Card panel =
                    new Card(property, config.copy(), context, false);

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
