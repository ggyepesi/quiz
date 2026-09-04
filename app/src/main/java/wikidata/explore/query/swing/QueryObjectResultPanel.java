package wikidata.explore.query.swing;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.ui.IdentityChip;

import objectview.render.RenderContext;
import objectview.Viewable;
import quiz.source.WikidataSource;
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
            IdentityChip::ofInstance;

    public QueryObjectResultPanel() {
        super(new BorderLayout());
        add(holder, BorderLayout.CENTER);
    }

    public RenderContext activeRenderContext() {
        return activeContext;
    }

    /** Presentation-only title decoration; identity remains the default. */
    public void cardDecorator(
            java.util.function.Function<Viewable, JComponent> decorator) {
        cardDecorator = decorator == null ? IdentityChip::ofInstance : decorator;
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

    @Override
    public void accept(ObjectQueryResult result) {
        SwingUtilities.invokeLater(() -> {
            holder.setVisible(false);
            holder.removeAll();
            activeContext = null;

            if (result == null
                    || result.objects() == null
                    || result.objects().isEmpty()) {
                holder.add(new JLabel("No objects."), BorderLayout.CENTER);
            } else {
                holder.add(buildView(result), BorderLayout.CENTER);
            }

            holder.setVisible(true);

            // One layout pass after the full replacement.
            holder.validate();
            holder.repaint();
        });
    }

    private JComponent buildView(ObjectQueryResult result) {
        Map<String, List<Viewable>> byType =
                ordered(groupByType(result.objects()), result.typeOrder());

        if (byType.size() <= 1) {
            return searchPanelView(result);
        }

        // One tab per type, each rendered by the SAME builder a single-type result gets.
        // Several types used to be laid out as side-by-side sections, which is a second
        // presentation of the same objects and a much smaller one: two types split the
        // width in half, so embedded in a panel rather than a wide window it came out as
        // search headers with no room for cards. Tabs give every type the full width,
        // and their order is the result's stated order — the production chain, when the
        // producer knows it.
        JTabbedPane byTypeTabs = new JTabbedPane();
        List<RenderContext> contexts = new ArrayList<>();
        for (Map.Entry<String, List<Viewable>> entry : byType.entrySet()) {
            List<Viewable> objects = entry.getValue();
            if (objects.isEmpty()) continue;
            JComponent view = searchPanelView(new ObjectQueryResult(
                    objects, result.primaryClass(), result.generatedSource()));
            contexts.add(activeContext);
            byTypeTabs.addTab(sectionTitle(entry.getKey(), objects.size()), view);
        }
        // The context belongs to what the reader is looking at: an action taken on the
        // shown tab must render through that tab's cards, not through whichever tab
        // happened to be built last.
        byTypeTabs.addChangeListener(event -> {
            int index = byTypeTabs.getSelectedIndex();
            if (index >= 0 && index < contexts.size()) activeContext = contexts.get(index);
        });
        if (!contexts.isEmpty()) activeContext = contexts.getFirst();

        return byTypeTabs;
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

    private Map<String, List<Viewable>> groupByType(List<Viewable> roots) {
        Map<String, List<Viewable>> byType =
                new LinkedHashMap<>();

        Set<Viewable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());

        Deque<Viewable> queue =
                new ArrayDeque<>(roots == null ? List.of() : roots);

        while (!queue.isEmpty()) {
            Viewable q = queue.poll();

            if (q == null || !seen.add(q)) {
                continue;
            }

            if (q instanceof wikidata.explore.extract.WikidataDynamicObject) {
                continue;
            }

            String type = q.typeName();

            if (type != null
                    && !type.isBlank()
                    && !"WikidataDynamicObject".equals(type)) {
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(q);
            }

            collectReferences(q, queue);
        }

        return byType;
    }

    private void collectReferences(
            Viewable q,
            Deque<Viewable> queue) {
        objectview.field.FieldSet fields = objectview.field.FieldSet.of(q);
        for (objectview.field.FieldRef field : fields.fields()) {
            addReferences(fields.read(field.name()), queue);
        }
    }

    private void addReferences(
            Object v,
            Deque<Viewable> queue) {

        if (v instanceof Viewable q) {
            queue.add(q);
        } else if (v instanceof Collection<?> c) {
            for (Object o : c) {
                addReferences(o, queue);
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                addReferences(o, queue);
            }
        }
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
                        // Stamp each instance with its Wikidata identity chip — same
                        // presentation the transform/curation views use, resolved here from the
                        // instance's native id (ModelBuilder has no curation sidecar).
                        .cardDecorator(cardDecorator)
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
