package quiz.ui;

import quiz.Quizable;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class VirtualizedCardList extends JComponent implements Scrollable {

    private static final int BUFFER = 6;
    private static final int DEFAULT_ROW = 140;
    // Below this the cards stop shrinking and the list scrolls horizontally
    // instead — so a narrow instances pane (the split's smaller side) doesn't
    // cram every card. At or above it, cards fill the viewport as before.
    private static final int MIN_CONTENT_WIDTH = 380;
    private static final int MAX_ESTIMATE_SAMPLES = 10;

    private static final boolean DEBUG =
            Boolean.getBoolean("quiz.nav.debug");

    private final Function<Quizable, JComponent> cardFactory;

    private List<Quizable> items = new ArrayList<>();

    private final Map<Quizable, JComponent> built =
            new IdentityHashMap<>();

    // Exact current height for a specific object.
    private final Map<Quizable, Integer> heights =
            new IdentityHashMap<>();

    private final Map<Quizable, Integer> indexByItem =
            new IdentityHashMap<>();

    // Stable class-level fallback for never-built cards.
    private final Map<Class<?>, HeightEstimate> estimatesByClass =
            new HashMap<>();

    private int[] tops = {0};

    private JViewport viewport;
    private boolean updating;
    private int navGeneration;

    public VirtualizedCardList(Function<Quizable, JComponent> cardFactory) {
        this.cardFactory = cardFactory;
        setLayout(null);
    }

    public void install(JScrollPane scroll) {
        scroll.setViewportView(this);
        viewport = scroll.getViewport();
        viewport.addChangeListener(e -> updateVisible());

        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.getVerticalScrollBar().setBlockIncrement(200);
    }

    public void setItems(List<Quizable> newItems) {
        items = new ArrayList<>(newItems == null ? List.of() : newItems);
        reindex();

        removeAll();
        built.clear();

        heights.keySet().removeIf(q -> !indexByItem.containsKey(q));

        rebuildTops();

        revalidate();
        repaint();
        updateVisible();
    }

    public void appendItem(Quizable q) {
        if (q == null) {
            return;
        }

        items.add(q);
        indexByItem.put(q, items.size() - 1);

        rebuildTops();
        revalidate();
        updateVisible();
    }

    private void reindex() {
        indexByItem.clear();

        for (int i = 0; i < items.size(); i++) {
            indexByItem.put(items.get(i), i);
        }
    }

    public List<Quizable> items() {
        return Collections.unmodifiableList(items);
    }

    public Quizable topVisibleItem() {
        if (viewport == null || items.isEmpty()) {
            return null;
        }

        int idx =
                indexAt(viewport.getViewPosition().y);

        return items.get(Math.max(0, Math.min(items.size() - 1, idx)));
    }

    public JComponent builtCard(Quizable q) {
        return built.get(q);
    }

    public JComponent buildIfNeeded(Quizable q) {
        int i = indexOf(q);
        if (i < 0) {
            return null;
        }

        JComponent card =
                built.get(q);

        if (card == null) {
            card = buildCard(q);
        }

        positionCard(i, card);

        if (measureCardIfChanged(q, card)) {
            rebuildTops();
            positionCard(i, card);
            revalidate();
        }

        return card;
    }

    public JComponent ensureVisible(Quizable q) {
        int i = indexOf(q);
        if (i < 0) {
            return null;
        }

        buildIfNeeded(q);

        scrollRectToVisible(new Rectangle(
                0,
                tops[i],
                1,
                rowHeight(q)));

        updateVisible();

        return built.get(q);
    }

    public JComponent navigateToTop(Quizable q) {
        int i = indexOf(q);

        if (i < 0 || viewport == null) {
            return null;
        }

        navGeneration++;

        if (DEBUG) {
            System.err.printf(
                    "[nav] start q=%s i=%d top=%d total=%d extent=%d%n",
                    q.getDisplayName(),
                    i,
                    tops[i],
                    totalHeight(),
                    viewport.getExtentSize().height);
        }

        buildIfNeeded(q);
        rebuildTops();
        scrollIndexToTop(i);
        updateVisible();

        rebuildTops();
        scrollIndexToTop(i);
        updateVisible();

        int gen = navGeneration;

        SwingUtilities.invokeLater(() -> {
            if (gen == navGeneration) {
                repinIfDrifted(q, i);
            }
        });

        if (DEBUG) {
            JComponent card = built.get(q);

            System.err.printf(
                    "[nav] after q=%s viewY=%d cardY=%d cardH=%d top=%d%n",
                    q.getDisplayName(),
                    viewport.getViewPosition().y,
                    card != null ? card.getY() : -1,
                    card != null ? card.getHeight() : -1,
                    tops[i]);
        }

        return built.get(q);
    }

    private void repinIfDrifted(Quizable q, int expectedIndex) {
        if (viewport == null || indexOf(q) != expectedIndex) {
            return;
        }

        rebuildTops();

        int wantY =
                clampTop(expectedIndex);

        int haveY =
                viewport.getViewPosition().y;

        if (Math.abs(haveY - wantY) > 1) {
            scrollIndexToTop(expectedIndex);
            updateVisible();
        }
    }

    private void scrollIndexToTop(int i) {
        setSize(
                Math.max(getWidth(), effectiveWidth()),
                preferredContentHeight());

        viewport.setViewPosition(
                new Point(0, clampTop(i)));
    }

    private int clampTop(int i) {
        return Math.max(0, Math.min(tops[i], maxScrollY()));
    }

    // The furthest you can scroll. Two needs, whichever is larger:
    //  - pin ANY card's top to the viewport top (navigation) -> the last card's
    //    top, tops[size-1];
    //  - reach the BOTTOM of a last card taller than the viewport (reading) ->
    //    content height minus one viewport.
    // Using only the last card's top left a tall last card's bottom unreachable.
    private int maxScrollY() {
        if (items.isEmpty()) {
            return 0;
        }
        int extent = viewport != null ? viewport.getExtentSize().height : 0;
        int lastTop = tops[items.size() - 1];
        return Math.max(lastTop, Math.max(0, contentHeight() - extent));
    }

    // Total laid-out height: the sentinel tops[size] is the bottom of the last card.
    private int contentHeight() {
        return items.isEmpty() ? 0 : tops[items.size()];
    }

    private int preferredContentHeight() {
        int extent =
                viewport != null ? viewport.getExtentSize().height : 0;

        return maxScrollY() + extent;
    }

    int topOf(Quizable q) {
        int i = indexOf(q);
        return i < 0 ? -1 : tops[i];
    }

    int totalHeight() {
        return tops.length == 0 ? 0 : tops[tops.length - 1];
    }

    private int indexOf(Quizable q) {
        Integer i = indexByItem.get(q);
        return i == null ? -1 : i;
    }

    private int rowHeight(Quizable q) {
        Integer exact =
                heights.get(q);

        if (exact != null) {
            return exact;
        }

        if (q != null) {
            HeightEstimate estimate =
                    estimatesByClass.get(q.getClass());

            if (estimate != null) {
                return estimate.value();
            }
        }

        return DEFAULT_ROW;
    }

    private int effectiveWidth() {
        int vw = viewport != null && viewport.getWidth() > 0
                ? viewport.getWidth()
                : Math.max(1, getWidth());
        // Never lay cards out narrower than the floor — the extra width becomes
        // horizontal scroll rather than squeezing the content.
        return Math.max(vw, MIN_CONTENT_WIDTH);
    }

    private void positionCard(int index, JComponent card) {
        Quizable q =
                items.get(index);

        card.setBounds(
                0,
                tops[index],
                effectiveWidth(),
                rowHeight(q));
    }

    // Lay a card's whole subtree out top-down at its current width, so leaf text
    // components know the width they'll wrap at before we read preferred heights.
    private static void layoutTree(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container cc) {
                layoutTree(cc);
            }
        }
    }

    private boolean measureCardIfChanged(
            Quizable q,
            JComponent card) {

        if (q == null || card == null) {
            return false;
        }

        // positionCard() sized the card to effectiveWidth(), but its children
        // aren't laid out yet — so wrapping text would measure at its fallback
        // width and report too short a height (then clip). Lay out the subtree at
        // the real width first, so getPreferredSize() reflects the actual wrapping.
        card.setSize(effectiveWidth(), card.getHeight());
        layoutTree(card);

        int measured =
                Math.max(1, card.getPreferredSize().height);

        Integer known =
                heights.get(q);

        if (known != null && known == measured) {
            return false;
        }

        heights.put(q, measured);

        // Exact object height always changes.
        // Class estimate only learns conservatively from normal-looking cards.
        updateClassEstimate(q, measured, known);

        return true;
    }

    private void updateClassEstimate(
            Quizable q,
            int measured,
            Integer previousExactHeight) {

        if (q == null) {
            return;
        }

        HeightEstimate estimate =
                estimatesByClass.computeIfAbsent(
                        q.getClass(),
                        k -> new HeightEstimate());

        /*
         * Learn only when:
         *  - this object was not measured before, or
         *  - its new measurement is not a large expansion outlier.
         *
         * A collapsed long-text object may still be taller than average;
         * that is okay as its exact object height is stored. But it should
         * not dominate the class estimate.
         */
        if (previousExactHeight != null) {
            int oldEstimate = estimate.value();

            if (measured > oldEstimate * 2) {
                return;
            }
        }

        estimate.addSample(measured);
    }

    private void rebuildTops() {
        tops =
                new int[items.size() + 1];

        int y =
                0;

        for (int i = 0; i < items.size(); i++) {
            tops[i] = y;
            y += rowHeight(items.get(i));
        }

        tops[items.size()] = y;
    }

    private int indexAt(int y) {
        if (items.isEmpty()) {
            return 0;
        }

        int lo = 0;
        int hi = items.size() - 1;

        while (lo < hi) {
            int mid =
                    (lo + hi + 1) >>> 1;

            if (tops[mid] <= y) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return lo;
    }

    private void updateVisible() {
        if (viewport == null || items.isEmpty() || updating) {
            return;
        }

        updating =
                true;

        try {
            for (int pass = 0; pass < 8 && updateVisibleOnce(); pass++) {
                // settle
            }
        } finally {
            updating =
                    false;
        }

        repaint();
    }

    private boolean updateVisibleOnce() {
        Rectangle view =
                viewport.getViewRect();

        int first =
                Math.max(0, indexAt(view.y) - BUFFER);

        int last =
                Math.min(
                        items.size() - 1,
                        indexAt(view.y + view.height) + BUFFER);

        int anchorIdx =
                indexAt(view.y);

        int withinAnchor =
                view.y - tops[anchorIdx];

        syncBuiltRange(first, last);

        boolean heightsChanged =
                false;

        for (int i = first; i <= last; i++) {
            Quizable q =
                    items.get(i);

            JComponent card =
                    built.get(q);

            if (card == null) {
                card = buildCard(q);
            }

            positionCard(i, card);

            if (measureCardIfChanged(q, card)) {
                heightsChanged = true;
            }
        }

        if (heightsChanged) {
            rebuildTops();

            for (int i = first; i <= last; i++) {
                JComponent card =
                        built.get(items.get(i));

                if (card != null) {
                    positionCard(i, card);
                }
            }

            revalidate();
        }

        int newY =
                Math.max(0, tops[anchorIdx] + withinAnchor);

        newY =
                Math.min(newY, maxScrollY());

        boolean moved =
                newY != view.y;

        if (moved) {
            viewport.setViewPosition(
                    new Point(view.x, newY));
        }

        return heightsChanged || moved;
    }

    private JComponent buildCard(Quizable q) {
        JComponent card =
                cardFactory.apply(q);

        built.put(q, card);
        add(card);

        return card;
    }

    private void syncBuiltRange(int first, int last) {
        Set<Quizable> keep =
                Collections.newSetFromMap(new IdentityHashMap<>());

        for (int i = Math.max(0, first);
             i <= last && i < items.size();
             i++) {

            keep.add(items.get(i));
        }

        for (Iterator<Map.Entry<Quizable, JComponent>> it =
             built.entrySet().iterator();
             it.hasNext(); ) {

            Map.Entry<Quizable, JComponent> e =
                    it.next();

            if (!keep.contains(e.getKey())) {
                remove(e.getValue());
                it.remove();
            }
        }
    }

    public String diagnostics() {
        StringBuilder sb =
                new StringBuilder();

        int total =
                totalHeight();

        int viewY =
                viewport != null ? viewport.getViewPosition().y : 0;

        int extent =
                viewport != null ? viewport.getExtentSize().height : 0;

        sb.append(String.format(
                "VirtualizedCardList: items=%d built=%d measured=%d classEstimates=%d%n"
                        + "  viewY=%d (%.1f%%) extent=%d total=%d maxScrollY=%d%n",
                items.size(),
                built.size(),
                heights.size(),
                estimatesByClass.size(),
                viewY,
                total > 0 ? 100.0 * viewY / total : 0,
                extent,
                total,
                maxScrollY()));

        List<Map.Entry<Quizable, JComponent>> byScreenY =
                new ArrayList<>(built.entrySet());

        byScreenY.sort(
                Comparator.comparingInt(a -> a.getValue().getY()));

        int prevIdx =
                Integer.MIN_VALUE;

        for (Map.Entry<Quizable, JComponent> e : byScreenY) {
            Quizable q =
                    e.getKey();

            JComponent c =
                    e.getValue();

            int idx =
                    indexOf(q);

            int trackedTop =
                    idx >= 0 ? tops[idx] : -1;

            String flags =
                    "";

            if (c.getY() != trackedTop) {
                flags += " OFFSET_MISMATCH(screenY!=tracked)";
            }

            if (idx <= prevIdx) {
                flags += " ORDER_BREAK(screen order != list order)";
            }

            prevIdx =
                    idx;

            sb.append(String.format(
                    "  screenY=%d h=%d | listIdx=%d trackedTop=%d | exact=%s | %s%s%n",
                    c.getY(),
                    c.getHeight(),
                    idx,
                    trackedTop,
                    heights.containsKey(q),
                    q.getDisplayName(),
                    flags));
        }

        sb.append("Class estimates:\n");

        for (Map.Entry<Class<?>, HeightEstimate> e : estimatesByClass.entrySet()) {
            sb.append(String.format(
                    "  %s -> %d (%d samples)%n",
                    e.getKey().getSimpleName(),
                    e.getValue().value(),
                    e.getValue().sampleCount()));
        }

        return sb.toString();
    }

    public void rebuild() {
        setItems(new ArrayList<>(items));
    }

    @Override
    public void doLayout() {
        updateVisible();
    }

    @Override
    public Dimension getPreferredSize() {
        // effectiveWidth() applies the floor, so a viewport narrower than it
        // yields a wider preferred size → the horizontal scrollbar appears.
        return new Dimension(effectiveWidth(), preferredContentHeight());
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
            Rectangle r,
            int orient,
            int dir) {

        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(
            Rectangle r,
            int orient,
            int dir) {

        return orient == SwingConstants.VERTICAL
                ? r.height - 20
                : r.width - 20;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        // Fill the viewport when it's wide enough; below the floor, don't track —
        // the list keeps its (wider) preferred width and scrolls horizontally.
        return viewport == null || viewport.getWidth() >= MIN_CONTENT_WIDTH;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    // Package-private for a direct unit test.
    static final class HeightEstimate {
        private final List<Integer> samples =
                new ArrayList<>();

        // Cached median of samples, recomputed only on change, so value() (called
        // for every unbuilt card in rebuildTops) is O(1) instead of sort-per-call.
        private int cached = DEFAULT_ROW;

        void addSample(int h) {
            if (h <= 0) {
                return;
            }

            if (samples.size() < MAX_ESTIMATE_SAMPLES) {
                samples.add(h);
                recompute();
                return;
            }

            /*
             * Only refine with near-normal values. Expanded cards and
             * unusually long text objects keep their exact object height,
             * but should not move the class estimate.
             */
            if (h <= cached * 2) {
                samples.removeFirst();
                samples.add(h);
                recompute();
            }
        }

        int value() {
            return cached;
        }

        int sampleCount() {
            return samples.size();
        }

        private void recompute() {
            if (samples.isEmpty()) {
                cached = DEFAULT_ROW;
                return;
            }

            List<Integer> copy =
                    new ArrayList<>(samples);

            Collections.sort(copy);

            cached = copy.get(copy.size() / 2);
        }
    }
}