package domain.workbench.execution.ui;

import wikidata.ui.WikidataLinks;

import objectview.demo.MultiView;
import objectview.render.GroupView;
import objectview.render.RenderContext;
import objectview.Viewable;
import objectview.search.MultiSearchBar;
import objectview.search.SearchPanel;
import objectview.search.SearchTabMarker;
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
import java.util.LinkedHashSet;
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
    private final List<JButton> resultActions = new ArrayList<>();
    private boolean resultActionsEnabled = true;
    /** The sections this panel currently shows, not a selection owned by its caller. */
    private Map<String, List<Viewable>> displayedObjectsByType = Map.of();
    private int classesPerTab = MultiView.DEFAULT_SECTIONS_PER_TAB;
    /** Presentation state for this result window. It is deliberately neither project
     * data nor instance data. Storing the exceptions means a new class starts visible. */
    private final Set<String> hiddenClassSections = new LinkedHashSet<>();
    private java.util.function.Consumer<Set<String>> hiddenClassSectionsListener =
            ignored -> { };
    private Runnable displayedObjectsListener = () -> { };
    private java.util.function.Function<Viewable, JComponent> cardDecorator =
            ignored -> null;
    private Map<String, objectview.group.ViewableGroup<?>> configuredGroups = Map.of();
    /** The domain whose class schemas type the shown sections; when set, each section's
     *  fields are enumerated from its class schema, never from an instance or the Java
     *  carrier class (WikidataDynamicObject). */
    private domain.DomainModel schemaDomain;
    private Map<String, Set<String>> unusedSourceFields = Map.of();

    public QueryObjectResultPanel() {
        super(new BorderLayout());
        add(holder, BorderLayout.CENTER);
    }

    public RenderContext activeRenderContext() {
        return activeContext;
    }

    /**
     * The exact class sections and roots currently displayed in this panel.
     *
     * <p>Actions placed beside this view must use this rather than an unrelated
     * configuration-tree selection. The lists are the section roots, not objects
     * rediscovered by walking their references.
     */
    public Map<String, List<Viewable>> displayedObjectsByType() {
        return displayedObjectsByType;
    }

    public void onDisplayedObjectsChanged(Runnable listener) {
        displayedObjectsListener = listener == null ? () -> { } : listener;
    }

    /** Restores presentation preferences without reporting a user action. */
    public void hiddenClassSections(Collection<String> hidden) {
        hiddenClassSections.clear();
        if (hidden != null) hidden.stream()
                .filter(name -> name != null && !name.isBlank())
                .forEach(hiddenClassSections::add);
    }

    public Set<String> hiddenClassSections() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(hiddenClassSections));
    }

    public void onHiddenClassSectionsChanged(
            java.util.function.Consumer<Set<String>> listener) {
        hiddenClassSectionsListener = listener == null ? ignored -> { } : listener;
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
        resultActions.clear();
        activeContext = null;
        displayedObjectsByType = Map.of();
        hiddenClassSections.clear();
        holder.removeAll();
        holder.repaint();
        displayedObjectsListener.run();
    }

    public void setResultActionsEnabled(boolean enabled) {
        resultActionsEnabled = enabled;
        resultActions.forEach(button -> button.setEnabled(enabled));
    }

    private JButton resultAction(String label) {
        JButton button = new JButton(label);
        button.setEnabled(resultActionsEnabled);
        resultActions.add(button);
        return button;
    }

    /** The domain whose class schemas the shown instances are rendered with, or
     *  {@code null} to use each object's own. */
    public void schemaDomain(domain.DomainModel domain) {
        schemaDomain = domain;
        unusedSourceFields = domain == null ? Map.of() : UnusedSourceFields.byClass(domain);
    }

    private Set<String> hiddenFields(String type) {
        Set<String> hidden = new LinkedHashSet<>(schemaDomain.structuralFields(type));
        hidden.addAll(unusedSourceFields.getOrDefault(type, Set.of()));
        return hidden;
    }

    /** The explicit group-class presentations for the result currently being shown. */
    public void configuredGroups(
            Collection<domain.transform.app.ConfiguredGroups.Binding> bindings) {
        Map<String, objectview.group.ViewableGroup<?>> roots = new LinkedHashMap<>();
        if (bindings != null) {
            for (domain.transform.app.ConfiguredGroups.Binding binding : bindings) {
                if (binding != null && binding.root() != null) {
                    roots.put(binding.groupClass(), binding.root());
                }
            }
        }
        configuredGroups = Collections.unmodifiableMap(roots);
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

    /** Opens the ordinary roots of a saved domain in the shared multi-instance view. */
    public void acceptDomain(domain.DomainModel domain) {
        acceptSavedRoots(domain.memberRoots(), Map.of());
    }

    /** Saved roots are already classified; unlike query results, they need no reference walk. */
    public void acceptSavedRoots(Collection<? extends Viewable> roots, Map<String, GroupedSection> peers) {
        Map<String, List<Viewable>> ordinary = new LinkedHashMap<>();
        for (Viewable member : roots) {
            ordinary.computeIfAbsent(member.typeName(), ignored -> new ArrayList<>()).add(member);
        }
        acceptGrouped(null, ordinary, peers);
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
            List<GroupAction> actions,
            List<process.swing.workflow.ProcessWorkflowResults.SelectionAction> selectionActions,
            Viewable shapeSample,
            java.util.function.Function<Viewable, JComponent> decoration) {
        public GroupedSection {
            selectionActions = selectionActions == null ? List.of() : List.copyOf(selectionActions);
            all = all == null ? List.of() : List.copyOf(all);
            Map<String, List<Viewable>> copied = new LinkedHashMap<>();
            if (partitions != null) partitions.forEach((name, values) ->
                    copied.put(name, values == null ? List.of() : List.copyOf(values)));
            partitions = Collections.unmodifiableMap(copied);
            actions = actions == null ? List.of() : List.copyOf(actions);
            decoration = decoration == null ? ignored -> null : decoration;
        }

        public GroupedSection(
                List<Viewable> all, Map<String, List<Viewable>> partitions) {
            this(all, partitions, List.of(), List.of(), null, null);
        }

        public GroupedSection(List<Viewable> all, Map<String, List<Viewable>> partitions,
                              List<GroupAction> actions) {
            this(all, partitions, actions, List.of(), null, null);
        }

        public GroupedSection(List<Viewable> all, Map<String, List<Viewable>> partitions,
                              List<GroupAction> actions,
                              List<process.swing.workflow.ProcessWorkflowResults.SelectionAction>
                                      selectionActions) {
            this(all, partitions, actions, selectionActions, null, null);
        }

        public static GroupedSection of(
                List<? extends Viewable> all,
                Map<String, ? extends List<? extends Viewable>> partitions) {
            return new GroupedSection(all == null ? List.of() : List.copyOf(all),
                    copyPartitions(partitions), List.of(), List.of(), null, null);
        }

        public static GroupedSection of(
                List<? extends Viewable> all,
                Map<String, ? extends List<? extends Viewable>> partitions,
                List<GroupAction> actions) {
            return new GroupedSection(all == null ? List.of() : List.copyOf(all),
                    copyPartitions(partitions), actions, List.of(), null, null);
        }

        /** As {@link #of(List, Map, List)}, with edits that act on the selected members —
         *  the same construct a workflow's result tabs use. */
        public static GroupedSection of(
                List<? extends Viewable> all,
                Map<String, ? extends List<? extends Viewable>> partitions,
                List<GroupAction> actions,
                List<process.swing.workflow.ProcessWorkflowResults.SelectionAction> selectionActions) {
            return new GroupedSection(all == null ? List.of() : List.copyOf(all),
                    copyPartitions(partitions), actions, selectionActions, null, null);
        }

        /** A peer section whose rows keep their own model shape and decoration while
         * sharing the ordinary ObjectView navigation and search machinery. */
        public static GroupedSection presented(
                List<? extends Viewable> all,
                Map<String, ? extends List<? extends Viewable>> partitions,
                List<GroupAction> actions,
                List<process.swing.workflow.ProcessWorkflowResults.SelectionAction>
                        selectionActions,
                Viewable shapeSample,
                java.util.function.Function<Viewable, JComponent> decoration) {
            return new GroupedSection(all == null ? List.of() : List.copyOf(all),
                    copyPartitions(partitions), actions, selectionActions,
                    shapeSample, decoration);
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
        acceptGrouped(result, null, sections);
    }

    private void acceptGrouped(ObjectQueryResult result, Map<String, List<Viewable>> savedRoots,
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
            resultActions.clear();

            if ((savedRoots == null ? result == null || result.objects() == null || result.objects().isEmpty()
                    : savedRoots.isEmpty())
                    && shownSections.isEmpty()) {
                displayedObjectsByType = Map.of();
                holder.add(new JLabel("No objects."), BorderLayout.CENTER);
            } else {
                ObjectQueryResult shown = result == null
                        ? new ObjectQueryResult(List.of(), null, "") : result;
                holder.add(buildView(shown, savedRoots, shownSections), BorderLayout.CENTER);
            }

            holder.setVisible(true);

            // One layout pass after the full replacement.
            holder.validate();
            holder.repaint();
            displayedObjectsListener.run();
        });
    }

    private JComponent buildView(ObjectQueryResult result, Map<String, List<Viewable>> savedRoots,
                                 Map<String, GroupedSection> peerSections) {
        // The result answers what it holds, by type. The panel used to walk the object
        // graph itself, so the headings it drew and the count the sample reported were
        // two rules for one question and disagreed the moment a result carried more
        // than the class that was asked for.
        // Owned components are instances too. Their ownership controls production and
        // identity, not visibility: showing Name here is the same class/instance view
        // TransformApp uses, and lets Person.structuredName navigate to the full card.
        var peers = peerSections.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().all(), (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<Viewable>> byType = savedRoots == null
                ? sections(result, peers) : new LinkedHashMap<>(savedRoots);
        if (savedRoots != null) byType.putAll(peers);
        if (savedRoots == null && peerSections.isEmpty() && byType.size() <= 1
                && byType.keySet().stream().noneMatch(configuredGroups::containsKey)) {
            showAllInDisplayedObjects(byType);
            return searchPanelView(result);
        }
        return multiView(byType, peerSections);
    }

    private JComponent multiView(Map<String, List<Viewable>> byType,
                                 Map<String, GroupedSection> peerSections) {
        Set<String> shownClasses = visibleClassesFor(byType.keySet());
        showInDisplayedObjects(byType, shownClasses);
        MultiView multi = new MultiView();
        if (schemaDomain != null) multi.context().setSchemas(schemaDomain::fieldSchema);
        Map<Viewable, java.util.function.Function<Viewable, JComponent>> peerDecorations =
                new IdentityHashMap<>();
        peerSections.values().forEach(group -> group.all().forEach(value ->
                peerDecorations.put(value, group.decoration())));
        multi.context().setCardDecorator(value -> {
            var decoration = peerDecorations.get(value);
            return decoration == null ? cardDecorator.apply(value) : decoration.apply(value);
        });
        multi.context().setValueLinker(WikidataLinks.valueLinker());
        multi.sectionNoun("Classes");
        multi.sectionsPerTab(classesPerTab);
        multi.onSectionsPerTabChanged(count -> classesPerTab = count);
        multi.visibleSections(shownClasses);
        multi.onVisibleSectionsChanged(selected -> {
            hiddenClassSections.removeAll(byType.keySet());
            byType.keySet().stream().filter(name -> !selected.contains(name))
                    .forEach(hiddenClassSections::add);
            showInDisplayedObjects(byType, selected);
            hiddenClassSectionsListener.accept(hiddenClassSections());
            displayedObjectsListener.run();
        });
        for (Map.Entry<String, List<Viewable>> entry : byType.entrySet()) {
            List<Viewable> values = entry.getValue();
            if (values.isEmpty()) continue;
            GroupedSection peer = peerSections.get(entry.getKey());
            objectview.group.ViewableGroup<?> group = configuredGroups.get(entry.getKey());
            if (peer != null) {
                multi.addDedicatedTab(entry.getKey(), sectionTitle(entry.getKey(), values.size()),
                        () -> groupedBrowser(peer, multi.context()), capped(values));
            } else if (group != null) {
                multi.addWideSection(entry.getKey(), sectionTitle(entry.getKey(), values.size()),
                        () -> {
                            GroupView view = new GroupView(group, multi.context());
                            view.showSelectedGroup();
                            return view;
                        }, capped(values));
            } else if (schemaDomain != null) {
                String type = entry.getKey();
                multi.addSection(type, sectionTitle(type, values.size()),
                        values.getFirst().getClass(), capped(values),
                        schemaDomain.configSample(type), hiddenFields(type),
                        schemaDomain.fieldTypes(type), null, null);
            } else {
                multi.addSection(entry.getKey(), sectionTitle(entry.getKey(), values.size()),
                        values.getFirst().getClass(), capped(values));
            }
        }
        multi.build(1);
        activeContext = multi.context();
        return multi;
    }

    private Set<String> visibleClassesFor(Collection<String> currentClasses) {
        Set<String> current = new LinkedHashSet<>(currentClasses);
        if (current.size() <= 1) return current;
        current.removeAll(hiddenClassSections);
        return current;
    }

    private void showAllInDisplayedObjects(Map<String, List<Viewable>> byType) {
        showInDisplayedObjects(byType, byType.keySet());
    }

    private void showInDisplayedObjects(
            Map<String, List<Viewable>> byType, Collection<String> shownClasses) {
        Set<String> shown = shownClasses == null
                ? Set.of() : new LinkedHashSet<>(shownClasses);
        Map<String, List<Viewable>> displayed = new LinkedHashMap<>();
        byType.forEach((name, values) -> {
            if (shown.contains(name)) displayed.put(name, List.copyOf(values));
        });
        displayedObjectsByType = Collections.unmodifiableMap(displayed);
    }

    private JComponent groupedBrowser(
            GroupedSection group, RenderContext context) {
        JTabbedPane decisions = new JTabbedPane();
        List<SearchPanel> searches = new ArrayList<>();
        List<SearchableView> shown = new ArrayList<>();
        List<Map.Entry<String, List<Viewable>>> views = new ArrayList<>();
        views.add(Map.entry("All", group.all()));
        views.addAll(group.partitions().entrySet());
        if (!group.selectionActions().isEmpty()) {
            // Edits act on what is selected, so several must be selectable.
            context.setSelectionEnabled(true);
            context.setMultipleSelectionEnabled(true);
        }
        for (Map.Entry<String, List<Viewable>> entry : views) {
            SearchableView view = decisionBrowser(entry.getValue(), context, group.shapeSample());
            if (view.search() != null) searches.add(view.search());
            shown.add(view);
            decisions.addTab(entry.getKey() + " (" + entry.getValue().size() + ")", view);
            if (view.search() != null) SearchTabMarker.follow(decisions, view, view.search());
        }
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        if (!group.actions().isEmpty()) {
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            for (GroupAction action : group.actions()) {
                JButton button = resultAction(action.label());
                button.addActionListener(ignored -> action.apply().run());
                actions.add(button);
            }
            header.add(actions);
        }
        if (!group.selectionActions().isEmpty()) {
            header.add(selectionActions(group, context, shown));
        }
        if (!searches.isEmpty()) header.add(new MultiSearchBar(searches));
        if (header.getComponentCount() > 0) panel.add(header, BorderLayout.NORTH);
        panel.add(decisions, BorderLayout.CENTER);
        // "All" is the canonical card owner for navigation. The decision subtabs are
        // filtered views of those same records, not distinct instances.
        group.all().forEach(value -> context.registerTopLevelRevealer(value,
                () -> decisions.setSelectedIndex(0)));
        return panel;
    }

    /**
     * Buttons that edit the selected members of {@code group}, and a line saying what the
     * last one did. Only this group's members are edited, whatever else the shared
     * selection holds, and the edited cards are redrawn in every tab that shows them.
     */
    private JComponent selectionActions(GroupedSection group, RenderContext context,
                                               List<SearchableView> shown) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel status = new JLabel("Ctrl/Cmd-click selects several.");
        java.util.Set<Viewable> members = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        members.addAll(group.all());
        for (process.swing.workflow.ProcessWorkflowResults.SelectionAction edit
                : group.selectionActions()) {
            JButton button = resultAction(edit.label());
            button.addActionListener(ignored -> {
                List<Viewable> chosen = context.selectedObjects().stream()
                        .filter(Viewable.class::isInstance).map(Viewable.class::cast)
                        .filter(members::contains).toList();
                if (chosen.isEmpty()) {
                    status.setText("Select entries of this graph first.");
                    return;
                }
                edit.apply().accept(chosen);
                shown.forEach(view -> view.refreshViewables(chosen));
                status.setText(edit.label() + ": " + chosen.size() + " entr"
                        + (chosen.size() == 1 ? "y" : "ies") + ".");
            });
            row.add(button);
        }
        row.add(status);
        return row;
    }

    private SearchableView decisionBrowser(
            List<Viewable> values, RenderContext context, Viewable shapeSample) {
        List<Viewable> shown = capped(values);
        Viewable sample = shapeSample == null && !shown.isEmpty()
                ? shown.getFirst() : shapeSample;
        return SearchableView.builder(shown)
                .sample(sample)
                .renderContext(context)
                .coordinated(true)
                .columns(1)
                .emptyMessage("(none)")
                .build();
    }

    static Map<String, List<Viewable>> sections(
            ObjectQueryResult result, Map<String, List<Viewable>> peerSections) {
        Map<String, List<Viewable>> byType =
                ordered(result.byType(), result.typeOrder());

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
            if (!(q instanceof domain.runtime.WikidataDynamicObject)) {
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
