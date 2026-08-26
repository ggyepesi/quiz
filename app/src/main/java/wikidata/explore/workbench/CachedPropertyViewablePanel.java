package wikidata.explore.workbench;

import objectview.view.ViewableListPanel;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class CachedPropertyViewablePanel extends JPanel {

    private final ViewableListPanel propertyList =
            new ViewableListPanel(
                    WikidataPropertyViewable.class,
                    "No cached Wikidata properties.");

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
        propertyList.onSelectionChanged(selected -> {
            if (selected instanceof WikidataPropertyViewable property) {
                propertySelected.accept(property);
            }
        });
        add(propertyList, BorderLayout.CENTER);
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

            propertyList.setViewables(properties);
            statusLabel.setText(
                    "Loaded cached properties: " + properties.size());

        } catch (Exception ex) {
            statusLabel.setText(
                    "Could not load property cache: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

}
