package quiz.ui;

import quiz.Quizable;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class VirtualizedCardList extends JComponent implements Scrollable {

    private static final int BUFFER = 6;
    private static final int DEFAULT_ROW = 140;
    private static final boolean DEBUG = Boolean.getBoolean("quiz.nav.debug");

    private final Function<Quizable, JComponent> cardFactory;

    private List<Quizable> items = new ArrayList<>();
    private final Map<Quizable, JComponent> built = new IdentityHashMap<>();
    private final Map<Quizable, Integer> heights = new IdentityHashMap<>();
    private final Map<Quizable, Integer> indexByItem = new IdentityHashMap<>();

    private int[] tops = {0};
    private int avgHeight = DEFAULT_ROW;

    private JViewport viewport;
    private boolean updating;

    // During navigateToTop(), the visible layout is forced to start here.
    private int pinnedTopIndex = -1;

    // Bumped per navigation so a rapid second nav cancels the first's deferred re-pin.
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
        rebuildTops();
        revalidate();
        repaint();
        updateVisible();
    }

    /** Rebuilds the identity→index map so {@link #indexOf} is O(1) (it's hit on
     *  every navigation/measure at tens of thousands of items). */
    private void reindex() {
        indexByItem.clear();
        for (int i = 0; i < items.size(); i++) {
            indexByItem.put(items.get(i), i);
        }
    }

    public List<Quizable> items() {
        return Collections.unmodifiableList(items);
    }

    /** A human-readable dump of internal state for diagnosing navigation: the
     *  viewport position (as a % of content), and every built card sorted by its
     *  ACTUAL on-screen y — with its list index, tracked offset, and a flag when
     *  the two disagree or the on-screen order doesn't match the list order. */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        int total = totalHeight();
        int viewY = viewport != null ? viewport.getViewPosition().y : 0;
        int extent = viewport != null ? viewport.getExtentSize().height : 0;
        sb.append(String.format(
                "VirtualizedCardList: items=%d built=%d measured=%d avgHeight=%d%n"
                        + "  viewY=%d (%.1f%%) extent=%d total=%d%n",
                items.size(), built.size(), heights.size(), avgHeight,
                viewY, total > 0 ? 100.0 * viewY / total : 0, extent, total));

        List<Map.Entry<Quizable, JComponent>> byScreenY = new ArrayList<>(built.entrySet());
        byScreenY.sort((a, b) -> Integer.compare(a.getValue().getY(), b.getValue().getY()));

        int prevIdx = Integer.MIN_VALUE;
        for (Map.Entry<Quizable, JComponent> e : byScreenY) {
            Quizable q = e.getKey();
            JComponent c = e.getValue();
            int idx = indexOf(q);
            int trackedTop = idx >= 0 ? tops[idx] : -1;

            String flags = "";
            if (c.getY() != trackedTop) {
                flags += " OFFSET_MISMATCH(screenY!=tracked)";
            }
            if (idx <= prevIdx) {
                flags += " ORDER_BREAK(screen order != list order)";
            }
            prevIdx = idx;

            sb.append(String.format(
                    "  screenY=%d h=%d | listIdx=%d trackedTop=%d | %s%s%n",
                    c.getY(), c.getHeight(), idx, trackedTop,
                    q.getDisplayName(), flags));
        }
        return sb.toString();
    }

    /** Clean reset: rebuild every offset and the visible window from scratch (same
     *  items, same order) — a recovery for a panel whose live layout got wedged. */
    public void rebuild() {
        setItems(new ArrayList<>(items));
    }

    /** The item currently at the top of the viewport, or null — used to keep your
     *  place across a resort (re-pin it at the top afterwards). */
    public Quizable topVisibleItem() {
        if (viewport == null || items.isEmpty()) {
            return null;
        }
        int idx = indexAt(viewport.getViewPosition().y);
        return items.get(Math.max(0, Math.min(items.size() - 1, idx)));
    }

    public JComponent builtCard(Quizable q) {
        return built.get(q);
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

    public JComponent buildIfNeeded(Quizable q) {
        int i = indexOf(q);
        if (i < 0) {
            return null;
        }

        JComponent card = built.get(q);
        if (card == null) {
            card = cardFactory.apply(q);
            built.put(q, card);
            add(card);
        }

        int width = effectiveWidth();
        card.setBounds(0, tops[i], width, rowHeight(q));
        measureCardIfChanged(q, card);

        return card;
    }

    public JComponent ensureVisible(Quizable q) {
        int i = indexOf(q);
        if (i < 0) {
            return null;
        }

        buildIfNeeded(q);
        scrollRectToVisible(new Rectangle(0, tops[i], 1, rowHeight(q)));
        updateVisible();

        return built.get(q);
    }

    /** Exact navigation: brings {@code q}'s card to the top of the viewport with an
     *  exact real-height layout from it downward, while the scroll position itself
     *  stays estimate-based. The single primitive behind reference jumps, search-hit
     *  navigation, and keeping your place across a resort. */
    public JComponent navigateToTop(Quizable q) {
        int i = indexOf(q);
        if (i < 0 || viewport == null) {
            return null;
        }

        rebuildTops();

        if (DEBUG) {
            System.err.printf(
                    "[nav] navigateToTop q=%s i=%d estimatedTop=%d total=%d extent=%d%n",
                    q.getDisplayName(), i, tops[i], totalHeight(),
                    viewport.getExtentSize().height);
        }

        pinIndexToTop(i);

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

        // A layout/repaint queued during the pinned passes can run afterwards and
        // move the viewport (most visible when jumping to the very first card, which
        // must sit at y=0). Re-pin once more, after those settle, IF it drifted.
        // Guarded by a generation so a rapid SECOND navigation cancels this stale
        // re-pin — otherwise the deferred re-pin from an earlier click yanks the
        // viewport back to the old target ("works once, then needs another click").
        int gen = ++navGeneration;
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (gen == navGeneration) {
                repinIfDrifted(q, i);
            }
        });

        return built.get(q);
    }

    private void repinIfDrifted(Quizable q, int i) {
        if (viewport == null || indexOf(q) != i) {
            return;
        }
        JComponent card = built.get(q);
        int wantY = sizeAndClampTop(i);
        if (card == null || viewport.getViewPosition().y != wantY || card.getY() != wantY) {
            pinIndexToTop(i);
        }
    }

    /** Pins index {@code i} to the viewport top and lays the visible window out
     *  exactly from it (real heights). One estimate-based scroll, then a corrected
     *  pass after the target and the cards below it have been measured. */
    private void pinIndexToTop(int i) {
        pinnedTopIndex = i;
        try {
            viewport.setViewPosition(new Point(0, sizeAndClampTop(i)));
            // The pinned pass is the important bit: the visible layout starts from
            // the target index, not from indexAt(view.y) — so it is immune to the
            // offset estimate being off for the never-built cards above it.
            updateVisiblePinnedFrom(i);

            // Measuring the target + downward cards can shift tops[]; re-scroll to
            // the corrected estimate and lay out exactly from the target once more.
            rebuildTops();
            viewport.setViewPosition(new Point(0, sizeAndClampTop(i)));
            updateVisiblePinnedFrom(i);
        } finally {
            pinnedTopIndex = -1;
        }
    }

    /** The largest scroll offset we allow: the top of the LAST card. Enough that
     *  any card (even the last) can be pinned to the viewport top, but NOT so much
     *  that you can scroll past the last card into empty padding. */
    private int maxScrollY() {
        return items.isEmpty() ? 0 : tops[items.size() - 1];
    }

    /** Sizes the view (just enough scroll-past to bring the last card's top to the
     *  viewport top) and returns the scroll offset that puts index {@code i} at the
     *  top — exactly, never clamped short. */
    private int sizeAndClampTop(int i) {
        int extent = viewport.getExtentSize().height;
        setSize(Math.max(getWidth(), viewport.getWidth()), maxScrollY() + extent);
        return Math.max(0, Math.min(tops[i], maxScrollY()));
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
        Integer h = heights.get(q);
        return h != null ? h : avgHeight;
    }

    private int effectiveWidth() {
        if (viewport != null && viewport.getWidth() > 0) {
            return viewport.getWidth();
        }
        return Math.max(1, getWidth());
    }

    private boolean measureCardIfChanged(Quizable q, JComponent card) {
        if (q == null || card == null) {
            return false;
        }

        int measured = Math.max(1, card.getPreferredSize().height);
        Integer known = heights.get(q);

        if (known != null && known == measured) {
            return false;
        }

        heights.put(q, measured);
        return true;
    }

    private void rebuildTops() {
        tops = new int[items.size() + 1];
        avgHeight = robustEstimate();

        int y = 0;
        for (int i = 0; i < items.size(); i++) {
            tops[i] = y;
            y += rowHeight(items.get(i));
        }
        tops[items.size()] = y;
    }

    private int robustEstimate() {
        if (heights.isEmpty()) {
            return avgHeight;
        }

        long sum = 0;
        int count = 0;
        for (int h : heights.values()) {
            sum += h;
            count++;
        }

        int mean = (int) (sum / count);
        int cap = Math.max(1, mean) * 2;

        long robustSum = 0;
        int robustCount = 0;

        for (int h : heights.values()) {
            if (h <= cap) {
                robustSum += h;
                robustCount++;
            }
        }

        return robustCount > 0
                ? Math.max(1, (int) (robustSum / robustCount))
                : Math.max(1, mean);
    }

    private int indexAt(int y) {
        if (items.isEmpty()) {
            return 0;
        }

        int lo = 0;
        int hi = items.size() - 1;

        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
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

        updating = true;
        try {
            if (pinnedTopIndex >= 0) {
                updateVisiblePinnedFrom(pinnedTopIndex);
            } else {
                for (int pass = 0; pass < 8 && updateVisibleOnce(); pass++) {
                    // settle
                }
            }
        } finally {
            updating = false;
        }

        repaint();
    }

    private boolean updateVisibleOnce() {
        Rectangle view = viewport.getViewRect();

        int first = Math.max(0, indexAt(view.y) - BUFFER);
        int last = Math.min(items.size() - 1, indexAt(view.y + view.height) + BUFFER);

        int anchorIdx = indexAt(view.y);
        int withinAnchor = view.y - tops[anchorIdx];

        syncBuiltRange(first, last);

        int width = effectiveWidth();
        boolean heightsChanged = false;

        for (int i = first; i <= last; i++) {
            Quizable q = items.get(i);
            JComponent card = built.get(q);

            if (card == null) {
                card = buildCard(q);
            }

            card.setBounds(0, tops[i], width, rowHeight(q));

            if (measureCardIfChanged(q, card)) {
                heightsChanged = true;
            }
        }

        if (heightsChanged) {
            rebuildTops();

            for (int i = first; i <= last; i++) {
                JComponent card = built.get(items.get(i));
                if (card != null) {
                    card.setBounds(0, tops[i], width, rowHeight(items.get(i)));
                }
            }

            revalidate();
        }

        int newY = Math.max(0, tops[anchorIdx] + withinAnchor);
        // Scroll range extends to the last card's top (maxScrollY), so any card can
        // sit at the top and the anchor re-pin isn't clamped short for near-end
        // targets (the "far reference doesn't work" drift) — but not past it.
        newY = Math.min(newY, maxScrollY());

        boolean moved = newY != view.y;
        if (moved) {
            viewport.setViewPosition(new Point(view.x, newY));
        }

        return heightsChanged || moved;
    }

    private void updateVisiblePinnedFrom(int index) {
        if (viewport == null || items.isEmpty()) {
            return;
        }

        Rectangle view = viewport.getViewRect();
        int width = effectiveWidth();

        int first = Math.max(0, index);
        int y = view.y;

        List<Integer> visibleIndices = new ArrayList<>();

        int limitY = view.y + view.height + BUFFER * avgHeight;

        for (int i = first; i < items.size() && y < limitY; i++) {
            visibleIndices.add(i);

            Quizable q = items.get(i);
            JComponent card = built.get(q);

            if (card == null) {
                card = buildCard(q);
            }

            card.setBounds(0, y, width, rowHeight(q));

            if (measureCardIfChanged(q, card)) {
                // exact height is now known
            }

            int h = rowHeight(q);
            card.setBounds(0, y, width, h);
            y += h;
        }

        int keepFirst = Math.max(0, first - BUFFER);
        int keepLast = visibleIndices.isEmpty()
                ? first
                : Math.min(items.size() - 1, visibleIndices.getLast() + BUFFER);

        syncBuiltRange(keepFirst, keepLast);

        rebuildTops();

        if (DEBUG) {
            System.err.printf(
                    "[nav] pinnedFrom index=%d target='%s' view.y=%d extent=%d "
                            + "rendering %d panels:%n",
                    index, items.get(index).getDisplayName(), view.y, view.height,
                    visibleIndices.size());
        }

        // Keep target exactly at the viewport top visually.
        y = view.y;
        for (int idx : visibleIndices) {
            Quizable q = items.get(idx);
            JComponent card = built.get(q);
            if (card != null) {
                int h = rowHeight(q);
                card.setBounds(0, y, width, h);
                if (DEBUG) {
                    System.err.printf("[nav]    render idx=%d y=%d h=%d name='%s'%n",
                            idx, y, h, q.getDisplayName());
                }
                y += h;
            }
        }

        if (DEBUG) {
            System.err.printf("[nav]  scroll now viewPos.y=%d (target should be at view.y=%d)%n",
                    viewport.getViewPosition().y, view.y);
        }

        revalidate();
        repaint();
    }

    private JComponent buildCard(Quizable q) {
        JComponent card = cardFactory.apply(q);
        built.put(q, card);
        add(card);
        return card;
    }

    private void syncBuiltRange(int first, int last) {
        Set<Quizable> keep = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int i = Math.max(0, first); i <= last && i < items.size(); i++) {
            keep.add(items.get(i));
        }

        for (Iterator<Map.Entry<Quizable, JComponent>> it = built.entrySet().iterator();
             it.hasNext(); ) {

            Map.Entry<Quizable, JComponent> e = it.next();

            if (!keep.contains(e.getKey())) {
                remove(e.getValue());
                it.remove();
            }
        }
    }

    @Override
    public void doLayout() {
        updateVisible();
    }

    @Override
    public Dimension getPreferredSize() {
        int w = viewport != null ? viewport.getWidth() : 600;
        // Just enough scroll-past that the last card can reach the viewport top —
        // no more, so you can't scroll past it into empty space.
        int extent = viewport != null ? viewport.getExtentSize().height : 0;
        return new Dimension(Math.max(1, w), maxScrollY() + extent);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int orient, int dir) {
        return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int orient, int dir) {
        return orient == javax.swing.SwingConstants.VERTICAL
                ? r.height - 20
                : r.width - 20;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}