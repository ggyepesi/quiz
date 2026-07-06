package quiz.ui;

import aux.GridBagUtils;
import quiz.Quizable;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class QuizablePanelView {
    private JFrame frame = null;

    private final List<Quizable> quizables = new ArrayList<>();
    private final List<RawImageEntry> rawImageEntries = new ArrayList<>();

    private final List<JPanel> cards = new ArrayList<>();
    private final Map<String, JPanel> cardsByName = new TreeMap<>();

    private JScrollPane cardsScrollPane;
    private VirtualizedCardList virtualList;

    // Shared across the initial render and any live additions so cards
    // resolve cross-references and class configs consistently.
    private QuizableRenderContext context;

    // Column count and trailing glue filler, remembered so a live add can
    // place the next card.
    private int columns = 1;

    private final List<QuizablePanelTargetListener> targetListeners =
            new ArrayList<>();

    // Optional externally-supplied context, shared with other views so a
    // reference click can navigate to a card in a sibling view.
    private QuizableRenderContext sharedContext;
    private boolean inPlaceNavigation;

    public QuizablePanelView() {
    }

    /**
     * Shares an external render context across views. All views using the
     * same context can resolve references to each other's cards. The owner
     * must pre-register every object (via {@link QuizableRenderContext}) so
     * cross-references render as chips rather than embedded cards.
     */
    public void setRenderContext(QuizableRenderContext context) {
        this.sharedContext = context;
    }

    /**
     * When true, single-clicking a reference whose target is a card in this
     * (or a shared) context navigates to it instead of opening a frame.
     */
    public void setInPlaceNavigation(boolean inPlaceNavigation) {
        this.inPlaceNavigation = inPlaceNavigation;
    }

    /**
     * Registers a listener notified when cards are added live (after the
     * frame is showing). {@link #createFrame} registers the internal
     * search panel automatically.
     */
    public void addTargetListener(QuizablePanelTargetListener listener) {
        if (listener != null && !targetListeners.contains(listener)) {
            targetListeners.add(listener);
        }
    }

    public Map<String, JPanel> getCardsByName() {
        return cardsByName;
    }

    public void addQuizable(Quizable quizable) {
        addQuizable(quizable, true, true);
    }

    public void addQuizable(Quizable quizable,
                            boolean addTitle,
                            boolean addListeners) {
        if (quizable != null) {
            quizables.add(quizable);
        }
    }

    public void addImagePane(String title, ImagePane imagePane) {
        if (imagePane != null) {
            rawImageEntries.add(new RawImageEntry(title, imagePane));
        }
    }

    public void createCardsPanel(int numColumns) {
        cards.clear();
        cardsByName.clear();
        columns = Math.max(1, numColumns);

        // Raw image entries (the enlarged-image view) are not Quizables, so they
        // can't go through the virtualized card list — give them their own panel.
        // Only ImagePane.showImageView uses this path, always without quizables.
        if (quizables.isEmpty() && !rawImageEntries.isEmpty()) {
            createRawImagePanel();
            return;
        }

        context = resolveContext();
        // Register every quizable as top-level up front, so a reference chip
        // navigates (isTopLevel is data-based, not panel-based) even to a card
        // that hasn't been built yet; the resolver builds it on demand.
        context.addTopLevels(quizables);

        // Virtualized: only the cards in (or near) the viewport are built. Scroll,
        // sort and re-layout are O(visible), not O(N), so it stays fast at tens of
        // thousands of cards.
        virtualList = new VirtualizedCardList(this::buildVirtualCard);
        // One resolver per section; the shared context tries each, so a reference to
        // a card in ANY section resolves (not just the last section to register).
        context.addTopLevelResolver(o ->
                o instanceof Quizable q ? virtualList.buildIfNeeded(q) : null);

        cardsScrollPane = new JScrollPane();
        cardsScrollPane.setDoubleBuffered(true);
        cardsScrollPane.getVerticalScrollBar().setUnitIncrement(20);
        RepaintManager.currentManager(cardsScrollPane)
                .setDoubleBufferingEnabled(true);

        virtualList.install(cardsScrollPane);
        virtualList.setItems(new ArrayList<>(quizables));
    }

    // The enlarged-image view: one holder per raw ImagePane, filling the frame
    // (no virtualization — there is only ever a handful, usually one).
    private void createRawImagePanel() {
        JPanel panel = new JPanel(new GridBagLayout());

        int row = 0;
        for (RawImageEntry entry : rawImageEntries) {
            JPanel holder = new JPanel(new GridBagLayout());
            holder.add(entry.imagePane,
                    GridBagUtils.gbc(
                            0, 0,
                            1.0, 1.0,
                            GridBagConstraints.CENTER,
                            GridBagConstraints.BOTH));
            cards.add(holder);
            if (entry.title != null) {
                cardsByName.putIfAbsent(entry.title, holder);
            }
            panel.add(holder,
                    GridBagUtils.gbc(
                            0, row++,
                            1.0, 1.0,
                            GridBagConstraints.CENTER,
                            GridBagConstraints.BOTH));
        }

        cardsScrollPane = new JScrollPane(panel);
        cardsScrollPane.getVerticalScrollBar().setUnitIncrement(20);
    }

    // Card factory for the virtualized list: build the card, register it, and
    // index it by name for getCardsByName.
    private javax.swing.JComponent buildVirtualCard(Quizable q) {
        QuizablePanel panel = buildQuizableCard(q);
        String name = q.getName();
        if (name != null && !name.isEmpty()) {
            cardsByName.putIfAbsent(name, panel);
        }
        return panel;
    }

    public VirtualizedCardList getVirtualList() {
        return virtualList;
    }

    private QuizablePanel buildQuizableCard(Quizable q) {
        QuizablePanelConfig cfg =
                QuizablePanelConfig.all(q.getClass())
                        .setAddListener(true)
                        .setThumb(true);

        context.putClassConfig(q.getClass(), cfg);

        QuizablePanel panel =
                new QuizablePanel(q, cfg, context, false);

        context.registerTopLevel(q, panel);

        tuneCardSize(panel);

        return panel;
    }

    /**
     * Adds a card after the frame is already showing: builds it, drops it
     * into the next grid slot, and notifies target listeners (the search
     * panel) so it stays searchable. Call on the EDT.
     *
     * Falls back to a deferred add if the cards panel hasn't been built
     * yet, in which case the card appears on the first render.
     */
    public void addQuizableLive(Quizable q) {
        if (q == null) {
            return;
        }

        quizables.add(q);

        if (virtualList == null) {
            return;
        }

        if (context == null) {
            context = resolveContext();
        }
        context.addTopLevel(q);

        virtualList.appendItem(q);

        // The card may not have been built yet (off-screen); notify with it if it
        // was, so the search panel can index it. A data-centric listener re-reads
        // the item list anyway.
        QuizablePanel panel = virtualList.builtCard(q) instanceof QuizablePanel p
                ? p : null;
        for (QuizablePanelTargetListener listener : targetListeners) {
            listener.quizablePanelsAdded(panel != null ? List.of(panel) : List.of());
        }
    }

    /**
     * Re-renders the card backing {@code q} in place after its fields
     * changed, and notifies target listeners (the search panel) so they
     * re-index / re-sort it. No-op if {@code q} has no card yet. Call on
     * the EDT.
     */
    public void refreshQuizable(Quizable q) {
        if (q == null || virtualList == null) {
            return;
        }

        QuizablePanel card = findCard(q);

        if (card == null) {
            return;   // not on screen — it rebuilds fresh when scrolled into view
        }

        card.refresh();

        virtualList.revalidate();
        virtualList.repaint();

        for (QuizablePanelTargetListener listener : targetListeners) {
            listener.quizablePanelsUpdated(List.of(card));
        }
    }

    /**
     * Refreshes {@code q}'s card if it already has one, otherwise adds it
     * live. Convenient for incremental feeds (e.g. a query log) that don't
     * track whether a given item has been rendered yet. Call on the EDT.
     */
    public void upsertQuizable(Quizable q) {
        if (q == null || virtualList == null) {
            return;
        }

        boolean known = false;
        for (Quizable item : virtualList.items()) {
            if (item == q) {
                known = true;
                break;
            }
        }
        if (known) {
            refreshQuizable(q);
        } else {
            addQuizableLive(q);
        }
    }

    private QuizableRenderContext resolveContext() {
        QuizableRenderContext c =
                sharedContext != null
                        ? sharedContext
                        : new QuizableRenderContext(quizables);

        if (sharedContext != null) {
            c.addTopLevels(quizables);
        }

        if (inPlaceNavigation) {
            c.setInPlaceNavigation(true);
        }

        return c;
    }

    private QuizablePanel findCard(Quizable q) {
        return virtualList != null && virtualList.builtCard(q) instanceof QuizablePanel qp
                ? qp : null;
    }

    public javax.swing.JComponent getCardsPanel() {
        return virtualList;
    }

    public JScrollPane getCardsScrollPane() {
        return cardsScrollPane;
    }

    public QuizableRenderContext getRenderContext() {
        return context;
    }

    private void createCardsBad() {
        QuizableRenderContext context =
                new QuizableRenderContext(quizables);

        // First pass: register class configs before rendering.
        for (Quizable q : quizables) {
            if (q == null) {
                continue;
            }

            QuizablePanelConfig cfg =
                    QuizablePanelConfig.all(q.getClass())
                            .setAddListener(true)
                            .setThumb(true);

            context.putClassConfig(q.getClass(), cfg);
        }

        // Second pass: create direct QuizablePanel cards.
        for (Quizable q : quizables) {
            if (q == null) {
                continue;
            }

            QuizablePanelConfig cfg =
                    context.configFor(q.getClass());

            if (cfg == null) {
                cfg = QuizablePanelConfig.all(q.getClass())
                        .setAddListener(true)
                        .setThumb(true);
            }

            QuizablePanel panel =
                    new QuizablePanel(q, cfg, context, false);

            context.registerTopLevel(q, panel);

            tuneCardSize(panel);

            cards.add(panel);

            String name = q.getName();

            if (name != null && !name.isEmpty()) {
                cardsByName.putIfAbsent(name, panel);
            }
        }

        // raw image entries unchanged
    }

    private void tuneCardSize(JPanel panel) {
        if (!containsImagePane(panel)) {
            return;
        }

        // A live card can be expanded in place (reference chips), so it must
        // keep growing past its initial size. Enforce the minimum footprint as
        // a floor that still lets the natural preferred size win, rather than
        // freezing it with setPreferredSize.
        if (panel instanceof QuizablePanel card) {
            card.setCardSizeFloor(new Dimension(260, 260));
            return;
        }

        // Static holders (raw image entries) never change, so a frozen size
        // is fine.
        Dimension pref = panel.getPreferredSize();
        panel.setPreferredSize(new Dimension(
                Math.max(pref.width, 260), Math.max(pref.height, 260)));
        panel.setMinimumSize(new Dimension(220, 220));
    }

    private boolean containsImagePane(Component c) {
        if (c instanceof ImagePane) {
            return true;
        }

        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsImagePane(child)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void createFrame(String title, int numColumns) {
        if (virtualList == null) {
            createCardsPanel(numColumns);
        }
        frame = new JFrame(
                quizables.size() == 1
                        ? title
                        : (title + ", " + quizables.size()));

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(6, 6));

        if (!quizables.isEmpty()) {
            Quizable first = quizables.getFirst();

            QuizableSearchPanel searchPanel =
                    new QuizableSearchPanel(first.getClass());

            searchPanel.setTarget(getCardsPanel(), cardsScrollPane);
            searchPanel.setRenderContext(context);
            addTargetListener(searchPanel);

            frame.add(searchPanel, BorderLayout.NORTH);
        }

        frame.add(cardsScrollPane, BorderLayout.CENTER);

        frame.setSize(1200, 700);
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
    }

    public void show() {
        if (frame != null) {
            frame.setVisible(true);
            frame.toFront();
        }
    }

    public void show(String title) {
        show(title, 1);
    }

    public void show(String title, int numColumns) {
        if (frame == null) {
            createFrame(title, numColumns);
        }

        show();
    }

    private record RawImageEntry(String title,
                                 ImagePane imagePane) {
    }
}