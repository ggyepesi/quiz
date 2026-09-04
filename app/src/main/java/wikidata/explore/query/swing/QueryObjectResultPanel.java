package wikidata.explore.query.swing;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.ui.IdentityChip;

import objectview.demo.MultiView;
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
        // The result answers what it holds, by type. The panel used to walk the object
        // graph itself, so the headings it drew and the count the sample reported were
        // two rules for one question and disagreed the moment a result carried more
        // than the class that was asked for.
        Map<String, List<Viewable>> byType =
                ordered(result.byType(), result.typeOrder());

        if (byType.size() <= 1) {
            return searchPanelView(result);
        }

        // Side by side, sharing ONE render context — which is the point of this layout
        // rather than a detail of it. Highlighting a reference in one section lights it
        // up in the other, and that works only while both render through the same
        // context. Tabs were tried here and gave each type its own SearchableView, so
        // every cross-panel highlight went dead: two panels showing related objects with
        // no way left to say they were related.
        MultiView multi =
                new MultiView();

        // MultiView supplies its own shared RenderContext rather than going through
        // SearchableView.Builder.cardDecorator(). Configure that context BEFORE build:
        // cards read their header decoration while they are constructed. Without this,
        // ModelBuilder showed QIDs for a one-type result but silently lost them as soon
        // as the result contained several types (the normal generated-domain case).
        multi.context().setCardDecorator(cardDecorator);

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
