package wikidata.explore.query.swing;

import objectview.demo.MultiView;
import objectview.field.DynamicFields;
import objectview.render.CardListView;
import objectview.render.RenderContext;
import objectview.search.SearchPanel;
import objectview.Viewable;
import objectview.ViewableAdapter;
import wikidata.explore.query.core.QueryResultSink;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
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

    public QueryObjectResultPanel() {
        super(new BorderLayout());
        add(holder, BorderLayout.CENTER);
    }

    public RenderContext activeRenderContext() {
        return activeContext;
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
                groupByType(result.objects());

        if (byType.size() <= 1) {
            return searchPanelView(result);
        }

        MultiView multi =
                new MultiView();

        for (Map.Entry<String, List<Viewable>> e : byType.entrySet()) {
            List<Viewable> full = e.getValue();

            if (full.isEmpty()) {
                continue;
            }

            multi.addSection(
                    sectionTitle(e.getKey(), full.size()),
                    full.getFirst().getClass(),
                    capped(full));
        }

        multi.build(1);
        activeContext = multi.context();

        return multi;
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

        if (q instanceof DynamicFields dyn) {
            for (Object v : dyn.dynamicFieldValues().values()) {
                addReferences(v, queue);
            }
        }

        for (Field f : ViewableAdapter.getAllFields(q.getClass())) {
            if (ViewableAdapter.isProvenanceField(f)) {
                continue;
            }

            try {
                f.setAccessible(true);
                addReferences(f.get(q), queue);
            } catch (Exception ignored) {
            }
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
        CardListView view =
                new CardListView();

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

        for (Viewable q : shown) {
            view.addViewable(q);
        }

        view.createCardsPanel(1);

        JPanel wrapped =
                new JPanel(new BorderLayout());

        Viewable first =
                shown.getFirst();

        SearchPanel searchPanel =
                new SearchPanel(first.getClass());

        searchPanel.setTarget(
                view.getCardsPanel(),
                view.getCardsScrollPane());

        searchPanel.setRenderContext(
                view.getRenderContext());

        view.addTargetListener(searchPanel);

        activeContext =
                view.getRenderContext();

        JComponent north =
                searchPanel;

        if (full.size() > MAX_CARDS) {
            JPanel header =
                    new JPanel(new BorderLayout());

            header.add(
                    new JLabel(cappedNote(full.size())),
                    BorderLayout.NORTH);

            header.add(
                    searchPanel,
                    BorderLayout.CENTER);

            north = header;
        }

        wrapped.add(north, BorderLayout.NORTH);
        wrapped.add(view.getCardsScrollPane(), BorderLayout.CENTER);

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