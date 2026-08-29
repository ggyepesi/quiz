package wikidata.explore.workbench;

import objectview.view.ViewableListPanel;
import objectview.render.GroupMembersView;
import wikidata.explore.WikidataProperty;
import wikidata.explore.WikidataPropertyStore;
import wikidata.explore.query.logical.DiscoverEntityRelationQuery;
import wikidata.explore.query.swing.SwingQueryRunner;

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
    private final JButton discoverGraph =
            new JButton("Explore entity relation");
    private final JButton useProperty =
            new JButton("Set selected property");
    private final JPanel selectionsHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final EntityRelationDiscoveryPanel relationGraph =
            new EntityRelationDiscoveryPanel();
    private JDialog relationDialog;
    private WikidataPropertyViewable selectedProperty;
    private WorkbenchSelections selections;

    private final List<WikidataPropertyViewable> properties =
            new ArrayList<>();

    private final Map<String, WikidataProperty> cache =
            new LinkedHashMap<>();

    public CachedPropertyViewablePanel() {
        super(new BorderLayout(6, 6));
        propertyList.onSelectionChanged(selected -> {
            if (selected instanceof WikidataPropertyViewable property) {
                selectedProperty = property;
                useProperty.setEnabled(selections != null);
                discoverGraph.setEnabled(true);
                relationGraph.property(property);
            } else {
                selectedProperty = null;
                useProperty.setEnabled(false);
                discoverGraph.setEnabled(false);
                relationGraph.property(null);
            }
        });
        add(propertyBrowser, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(6, 0));
        footer.add(statusLabel, BorderLayout.CENTER);
        useProperty.setEnabled(false);
        useProperty.setToolTipText("Remember this property for explicit use in configuration.");
        useProperty.addActionListener(event -> {
            if (selectedProperty != null && selections != null) {
                selections.property(selectedProperty.pid(), selectedProperty.getDisplayName());
            }
        });
        discoverGraph.setEnabled(false);
        discoverGraph.setToolTipText("Use the selected property as an edge between QID nodes.");
        discoverGraph.addActionListener(event -> showRelationGraph());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.add(selectionsHolder);
        actions.add(useProperty);
        actions.add(discoverGraph);
        footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        loadProperties();
    }

    public void setQueryRunner(SwingQueryRunner runner) {
        relationGraph.setQueryRunner(runner);
    }

    public void selections(WorkbenchSelections value) {
        selections = value;
        relationGraph.selections(value);
        selectionsHolder.removeAll();
        if (value != null) selectionsHolder.add(new SelectionsButton(value));
        selectionsHolder.revalidate();
        selectionsHolder.repaint();
        useProperty.setEnabled(selectedProperty != null && value != null);
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


    private void showRelationGraph() {
        relationGraph.property(selectedProperty);
        if (relationDialog == null || !relationDialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            relationDialog = new JDialog(owner, "Entity relation discovery",
                    Dialog.ModalityType.MODELESS);
            relationDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            relationDialog.setContentPane(relationGraph);
            relationDialog.setSize(1050, 720);
            relationDialog.setLocationRelativeTo(owner);
        }
        relationDialog.setVisible(true);
        relationDialog.toFront();
    }

    public void exploreEntityRelation(
            String pid,
            String startingQid,
            DiscoverEntityRelationQuery.Direction direction) {
        WikidataProperty property = cache.get(pid);
        if (property == null) return;
        selectedProperty = new WikidataPropertyViewable(property);
        relationGraph.property(selectedProperty);
        relationGraph.startingQid(startingQid);
        relationGraph.direction(direction);
        showRelationGraph();
    }

}
