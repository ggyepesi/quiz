package wikidata.explore.workbench;

import objectview.view.ViewableListPanel;
import objectview.render.GroupMembersView;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import workbench.SelectionsButton;
import workbench.WorkbenchSelections;

public class CachedPropertyViewablePanel extends JPanel {

    private final ViewableListPanel propertyList =
            new ViewableListPanel(
                    WikidataPropertyViewable.class,
                    "No cached Wikidata properties.");

    private final JLabel statusLabel =
            new JLabel(" ");
    private final JPanel propertyBrowser = new JPanel(new BorderLayout(6, 0));
    private final JPanel selectionsHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private WikidataPropertyViewable selectedProperty;
    private WorkbenchSelections selections;

    private final List<WikidataPropertyViewable> properties =
            new ArrayList<>();

    private final Map<String, WikidataProperty> cache =
            new LinkedHashMap<>();

    public CachedPropertyViewablePanel() {
        super(new BorderLayout(6, 6));
        propertyList.valueLinker(wikidata.ui.WikidataLinks.valueLinker());
        propertyList.onSelectionChanged(selected -> {
            if (selected instanceof WikidataPropertyViewable property) {
                selectedProperty = property;
            } else {
                selectedProperty = null;
            }
        });
        add(propertyBrowser, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(6, 0));
        footer.add(statusLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(selectionsHolder);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        loadProperties();
    }

    public void selections(WorkbenchSelections value) {
        selections = value;
        selectionsHolder.removeAll();
        if (value != null) {
            selectionsHolder.add(new SelectionsButton(value).action(
                    "Add highlighted property to reusable selections",
                    () -> selectedProperty != null,
                    this::setSelectedProperty));
        }
        selectionsHolder.revalidate();
        selectionsHolder.repaint();
    }

    private void setSelectedProperty() {
        if (selectedProperty != null && selections != null) {
            selections.property(selectedProperty.pid(), selectedProperty.getDisplayName());
        }
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
            showPropertyGroups();
            statusLabel.setText(
                    "Loaded cached properties: " + properties.size());

        } catch (Exception ex) {
            statusLabel.setText(
                    "Could not load property cache: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void showPropertyGroups() {
        Map<String, WikidataPropertyViewable> viewsByPid = new LinkedHashMap<>();
        for (WikidataPropertyViewable view : properties) {
            viewsByPid.put(view.pid(), view);
        }
        PropertyStructureGroups.PropertyGroup root = PropertyStructureGroups.build(
                new ArrayList<>(cache.values()), viewsByPid);
        GroupMembersView grouped = new GroupMembersView(
                root,
                group -> {
                    propertyList.setViewables(group == null ? List.of()
                            : group.getMembers().stream()
                                    .filter(WikidataPropertyViewable.class::isInstance)
                                    .map(WikidataPropertyViewable.class::cast).toList());
                    return propertyList;
                },
                JSplitPane.HORIZONTAL_SPLIT, false, 0.22, true);
        grouped.groups().setStatusText("Choose a structural property group");
        propertyBrowser.removeAll();
        propertyBrowser.add(grouped, BorderLayout.CENTER);
        propertyBrowser.revalidate();
        propertyBrowser.repaint();
        grouped.selectGroup(root, true);
    }


}
