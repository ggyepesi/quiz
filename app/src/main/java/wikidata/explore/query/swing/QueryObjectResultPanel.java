package wikidata.explore.query.swing;

import wikidata.ui.WikidataLinks;

import objectview.demo.MultiView;
import objectview.render.RenderContext;
import objectview.Viewable;
import objectview.search.MultiSearchBar;
import objectview.search.SearchPanel;
import objectview.view.SearchableView;
import work.QueryResultSink;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QueryObjectResultPanel
        extends JPanel
        implements QueryResultSink<ObjectQueryResult> {

    public enum ViewMode {
        SEARCH_PANEL,
        TYPE_PANEL_DEMO
    }

    private static final int MAX_CARDS = Integer.MAX_VALUE;

    private ViewMode viewMode = ViewMode.SEARCH_PANEL;

    private final JPanel holder =
            new JPanel(new BorderLayout());

    private RenderContext activeContext;
    private java.util.function.Function<Viewable, JComponent> cardDecorator =
            ignored -> null;

    public QueryObjectResultPanel() {
        super(new BorderLayout());
        add(holder, BorderLayout.CENTER);
    }

    public RenderContext activeRenderContext() {
        return activeContext;
    }

    /** Optional presentation-only title decoration. Datasource provenance is a field. */
    public void cardDecorator(
            java.util.function.Function<Viewable, JComponent> decorator) {
        cardDecorator = decorator == null ? ignored -> null : decorator;
    }

    public void viewMode(ViewMode viewMode) {
        this.viewMode =
                viewMode == null ? ViewMode.SEARCH_PANEL : viewMode;
    }

    public void clear() {
        activeContext = null;
        holder.removeAll();
        holder.repaint();
    }

    /**
     * A result on its own, with no peer sections beside it.
     *
     * <p>The peers are NOT remembered from the last call. They belong to the caller that
     * knows what is currently true — for graph annotations, the window that reads the
     * panel's results at the moment it opens. Held here instead, a run accepted after a
     * graph result was shown redrew the new instances beside the previous run's
     * annotation tabs, and a result discarded by a configuration change came back the
     * next time anything arrived.
     */
    @Override
    public void accept(ObjectQueryResult result) {
        acceptGrouped(result, Map.of());
    }

    /**
     * One peer section: a class shown beside the result's own types, optionally
     * partitioned into subtabs. Peers are roots in their own right — unlike references
     * reached while walking a generated instance, they must not pull their private
     * object graph into another class's section. Graph-constraint annotations use this.
     */
    public record GroupAction(String label, Runnable apply) {
        public GroupAction {
            label = label == null || label.isBlank() ? "Apply" : label;
            java.util.Objects.requireNonNull(apply, "apply");
        }
    }

    public record GroupedSection(
            List<Viewable> all, Map<String, List<Viewable>> partitions,
            List<GroupAction> actions) {
        public GroupedSection {
            all = all == null ? List.of() : List.copyOf(all);
            Map<String, List<Viewable>> copied = new LinkedHashMap<>();
            if (partitions != null) partitions.forEach((name, values) ->
                    copied.put(name, values == null ? List.of() : List.copyOf(values)));
            partitions = Collections.unmodifiableMap(copied);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        public GroupedSection(
                List<Viewable> all, Map<String, List<Viewable>> partitions) {
            this(all, partitions, List.of());
        }

        public static GroupedSection of(
                List<? extends Viewable> all,
                Map<String, ? extends List<? extends Viewable>> partitions) {
            return new GroupedSection(all == null ? List.of() : List.copyOf(all),
                    copyPartitions(partitions), List.of());
        }

        public static GroupedSection of(
                List<? extends Viewable> all,
                Map<String, ? extends List<? extends Viewable>> partitions,
                List<GroupAction> actions) {
            return new GroupedSection(all == null ? List.of() : List.copyOf(all),
                    copyPartitions(partitions), actions);
        }

        private static Map<String, List<Viewable>> copyPartitions(
                Map<String, ? extends List<? extends Viewable>> values) {
            Map<String, List<Viewable>> copied = new LinkedHashMap<>();
            if (values != null) values.forEach((name, members) -> copied.put(name,
                    members == null ? List.of() : List.copyOf(members)));
            return copied;
        }
    }

    public void acceptGrouped(ObjectQueryResult result,
                              Map<String, GroupedSection> sections) {
        // A peer holding nothing is not a peer: it draws no tab, so counting it as one
        // would move the ordinary classes out of their side-by-side layout and into
        // tabs to make room for a section that is never added.
        Map<String, GroupedSection> copied = new LinkedHashMap<>();
        if (sections != null) {
            sections.forEach((name, values) -> {
                if (values != null && !values.all().isEmpty()) copied.put(name, values);
            });
        }
        Map<String, GroupedSection> shownSections = Collections.unmodifiableMap(copied);
        SwingUtilities.invokeLater(() -> {
            holder.setVisible(false);
            holder.removeAll();
            activeContext = null;

            if ((result == null || result.objects() == null || result.objects().isEmpty())
                    && shownSections.isEmpty()) {
                holder.add(new JLabel("No objects."), BorderLayout.CENTER);
            } else {
                ObjectQueryResult shown = result == null
                        ? new ObjectQueryResult(List.of(), null, "") : result;
                holder.add(buildView(shown, shownSections), BorderLayout.CENTER);
            }

            holder.setVisible(true);

            // One layout pass after the full replacement.
            holder.validate();
            holder.repaint();
        });
    }

    private JComponent buildView(ObjectQueryResult result,
                                 Map<String, GroupedSection> peerSections) {
        // The result answers what it holds, by type. The panel used to walk the object
        // graph itself, so the headings it drew and the count the sample reported were
        // two rules for one question and disagreed the moment a result carried more
        // than the class that was asked for.
        // Without the parts: a class produced per owning instance is reached through
        // its owner and rendered inside it, so a heading of its own puts it beside the
        // classes it belongs to as though it were one of them. On Nobel that listed 989
        // structured names next to the prizes and the people they name.
        Map<String, List<Viewable>> byType = sections(result, peerSections.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().all(), (left, right) -> left,
                        LinkedHashMap::new)));

        if (peerSections.isEmpty() && byType.size() <= 1) {
            return searchPanelView(result);
        }
        if (peerSections.isEmpty()) return multiView(byType);

        RenderContext context = new RenderContext();
        context.setInPlaceNavigation(true);
        context.setCardDecorator(cardDecorator);
        context.setValueLinker(WikidataLinks.valueLinker());
        byType.values().forEach(values -> values.forEach(context::addTopLevel));

        JTabbedPane types = new JTabbedPane();
        for (Map.Entry<String, List<Viewable>> entry : byType.entrySet()) {
            String name = entry.getKey();
            List<Viewable> all = entry.getValue();
            GroupedSection grouped = peerSections.get(name);
            JComponent body = grouped == null
                    ? browser(all, context, false)
                    : groupedBrowser(grouped, context, types);
            int index = types.getTabCount();
            types.addTab(sectionTitle(name, all.size()), body);
            if (grouped == null) all.forEach(value -> context.registerTopLevelRevealer(
                    value, () -> types.setSelectedIndex(index)));
        }
        activeContext = context;
        return types;
    }

    /** Existing ordinary multi-class results stay simultaneously visible. */
    private JComponent multiView(Map<String, List<Viewable>> byType) {
        MultiView multi = new MultiView();
        multi.context().setCardDecorator(cardDecorator);
        multi.context().setValueLinker(WikidataLinks.valueLinker());
        for (Map.Entry<String, List<Viewable>> entry : byType.entrySet()) {
            List<Viewable> values = entry.getValue();
            if (!values.isEmpty()) multi.addSection(
                    sectionTitle(entry.getKey(), values.size()),
                    values.getFirst().getClass(), capped(values));
        }
        multi.build(1);
        activeContext = multi.context();
        return multi;
    }

    private JComponent groupedBrowser(
            GroupedSection group, RenderContext context, JTabbedPane owner) {
        JTabbedPane decisions = new JTabbedPane();
        List<SearchPanel> searches = new ArrayList<>();
        List<Map.Entry<String, List<Viewable>>> views = new ArrayList<>();
        views.add(Map.entry("All", group.all()));
        views.addAll(group.partitions().entrySet());
        for (Map.Entry<String, List<Viewable>> entry : views) {
            SearchableView view = browser(entry.getValue(), context, true);
            if (view.search() != null) searches.add(view.search());
            decisions.addTab(entry.getKey() + " (" + entry.getValue().size() + ")", view);
        }
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        if (!group.actions().isEmpty()) {
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            for (GroupAction action : group.actions()) {
                JButton button = new JButton(action.label());
                button.addActionListener(ignored -> action.apply().run());
                actions.add(button);
            }
            header.add(actions);
        }
        if (!searches.isEmpty()) header.add(new MultiSearchBar(searches));
        if (header.getComponentCount() > 0) panel.add(header, BorderLayout.NORTH);
        panel.add(decisions, BorderLayout.CENTER);
        // "All" is the canonical card owner for navigation. The decision subtabs are
        // filtered views of those same records, not distinct instances.
        group.all().forEach(value -> context.registerTopLevelRevealer(value, () -> {
            owner.setSelectedComponent(panel);
            decisions.setSelectedIndex(0);
        }));
        return panel;
    }

    private SearchableView browser(
            List<Viewable> values, RenderContext context, boolean coordinated) {
        List<Viewable> shown = capped(values);
        Viewable sample = shown.isEmpty() ? null : shown.getFirst();
        return SearchableView.builder(shown)
                .sample(sample)
                .renderContext(context)
                .coordinated(coordinated)
                .columns(1)
                .emptyMessage("(none)")
                .build();
    }

    static Map<String, List<Viewable>> sections(
            ObjectQueryResult result, Map<String, List<Viewable>> peerSections) {
        Map<String, List<Viewable>> byType =
                ordered(result.byTypeWithoutParts(), result.typeOrder());

        // A named graph constraint is a class of annotations beside the classes it
        // annotates.  Keep its records as an explicit section rather than walking the
        // candidate shells referenced by each annotation and mistaking those shells
        // for another copy of the generated output population.
        (peerSections == null ? Map.<String, List<Viewable>>of() : peerSections)
                .forEach((name, values) -> {
            if (name != null && !name.isBlank() && values != null && !values.isEmpty()) {
                byType.put(name, values);
            }
        });
        return byType;
    }

    /**
     * The producer's order first, then whatever it did not name.
     *
     * <p>Grouping discovers types by walking references, so without this the sections
     * come out in the order the walk happened to reach them — which for a sample of a
     * derived class puts its production chain in no particular order.
     */
    private static Map<String, List<Viewable>> ordered(
            Map<String, List<Viewable>> byType, List<String> typeOrder) {
        if (typeOrder == null || typeOrder.isEmpty()) return byType;
        Map<String, List<Viewable>> sorted = new LinkedHashMap<>();
        for (String type : typeOrder) {
            List<Viewable> objects = byType.get(type);
            if (objects != null) sorted.put(type, objects);
        }
        byType.forEach(sorted::putIfAbsent);
        return sorted;
    }




    private JComponent searchPanelView(ObjectQueryResult result) {
        List<Viewable> typed =
                new ArrayList<>();

        for (Viewable q : result.objects()) {
            if (!(q instanceof wikidata.explore.extract.WikidataDynamicObject)) {
                typed.add(q);
            }
        }

        List<Viewable> full =
                typed.isEmpty() ? result.objects() : typed;

        List<Viewable> shown =
                capped(full);

        if (shown.isEmpty()) {
            activeContext = null;
            return new JLabel("No typed objects.");
        }

        Viewable first =
                shown.getFirst();
        objectview.view.SearchableView browser =
                objectview.view.SearchableView.builder(shown)
                        .sample(first)
                        .cardDecorator(cardDecorator)
                        .valueLinker(WikidataLinks.valueLinker())
                        .build();
        activeContext = browser.renderContext();

        JPanel wrapped = new JPanel(new BorderLayout());

        if (full.size() > MAX_CARDS) {
            wrapped.add(new JLabel(cappedNote(full.size())), BorderLayout.NORTH);
        }
        wrapped.add(browser, BorderLayout.CENTER);

        return wrapped;
    }

    private static List<Viewable> capped(List<Viewable> objects) {
        return objects.size() <= MAX_CARDS
                ? objects
                : new ArrayList<>(objects.subList(0, MAX_CARDS));
    }

    private static String sectionTitle(
            String type,
            int total) {

        return total <= MAX_CARDS
                ? type
                : type + "  (showing " + MAX_CARDS + " of " + total + ")";
    }

    private static String cappedNote(int total) {
        return "Showing first " + MAX_CARDS + " of " + total
                + " — full set is saved + served in the web.";
    }
}
