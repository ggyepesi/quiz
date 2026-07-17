package wikidata.explore.query.swing;

import objectview.QuizableRenderContext;
import objectview.field.DynamicFields;
import quiz.Quizable;
import quiz.QuizableAdapter;
import objectview.MultiQuizableView;
import objectview.QuizablePanelView;
import objectview.QuizableSearchPanel;
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

    private QuizableRenderContext activeContext;

    public QueryObjectResultPanel() {
        super(new BorderLayout());
        add(holder, BorderLayout.CENTER);
    }

    public QuizableRenderContext activeRenderContext() {
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
        Map<String, List<Quizable>> byType =
                groupByType(result.objects());

        if (byType.size() <= 1) {
            return searchPanelView(result);
        }

        MultiQuizableView multi =
                new MultiQuizableView();

        for (Map.Entry<String, List<Quizable>> e : byType.entrySet()) {
            List<Quizable> full = e.getValue();

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

    private Map<String, List<Quizable>> groupByType(List<Quizable> roots) {
        Map<String, List<Quizable>> byType =
                new LinkedHashMap<>();

        Set<Quizable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());

        Deque<Quizable> queue =
                new ArrayDeque<>(roots == null ? List.of() : roots);

        while (!queue.isEmpty()) {
            Quizable q = queue.poll();

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
            Quizable q,
            Deque<Quizable> queue) {

        if (q instanceof DynamicFields dyn) {
            for (Object v : dyn.dynamicFieldValues().values()) {
                addReferences(v, queue);
            }
        }

        for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
            if (QuizableAdapter.isProvenanceField(f)) {
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
            Deque<Quizable> queue) {

        if (v instanceof Quizable q) {
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
        QuizablePanelView view =
                new QuizablePanelView();

        List<Quizable> typed =
                new ArrayList<>();

        for (Quizable q : result.objects()) {
            if (!(q instanceof wikidata.explore.extract.WikidataDynamicObject)) {
                typed.add(q);
            }
        }

        List<Quizable> full =
                typed.isEmpty() ? result.objects() : typed;

        List<Quizable> shown =
                capped(full);

        if (shown.isEmpty()) {
            activeContext = null;
            return new JLabel("No typed objects.");
        }

        for (Quizable q : shown) {
            view.addQuizable(q);
        }

        view.createCardsPanel(1);

        JPanel wrapped =
                new JPanel(new BorderLayout());

        Quizable first =
                shown.getFirst();

        QuizableSearchPanel searchPanel =
                new QuizableSearchPanel(first.getClass());

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

    private static List<Quizable> capped(List<Quizable> objects) {
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