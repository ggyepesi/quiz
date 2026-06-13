package quiz.ui;

import aux.GridBagUtils;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

public class QuizablePanelView {
    private JFrame frame = null;

    private final List<Quizable> quizables = new ArrayList<>();
    private final List<RawImageEntry> rawImageEntries = new ArrayList<>();

    private final List<JPanel> cards = new ArrayList<>();
    private final Map<String, JPanel> cardsByName = new TreeMap<>();

    private JPanel cardsPanel;
    private JScrollPane cardsScrollPane;

    // Shared across the initial render and any live additions so cards
    // resolve cross-references and class configs consistently.
    private QuizableRenderContext context;

    // Column count and trailing glue filler, remembered so a live add can
    // place the next card and keep the glue below it.
    private int columns = 1;
    private Component glueComponent;

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
        System.out.println(getClass().getName() + ".createCardsPanel for " + cards.size() + " cards...");
        cards.clear();
        cardsByName.clear();
        columns = Math.max(1, numColumns);

        cardsPanel = new JPanel(new GridBagLayout());
        cardsScrollPane = new JScrollPane(cardsPanel);

        cardsScrollPane.setDoubleBuffered(true);
        cardsScrollPane.getVerticalScrollBar().setUnitIncrement(20);
        cardsScrollPane.getHorizontalScrollBar().setUnitIncrement(20);
        cardsScrollPane.getVerticalScrollBar().setBlockIncrement(20);

        RepaintManager.currentManager(cardsScrollPane)
                .setDoubleBufferingEnabled(true);

        context = resolveContext();
        createCards();

        if (cards.isEmpty()) {
            return;
        }

        int row = 0;
        int col = 0;

        for (JPanel card : cards) {
            tuneCardSize(card);

            cardsPanel.add(card,
                    GridBagUtils.gbc(
                            col, row,
                            1.0, 0.0,
                            GridBagConstraints.NORTHWEST,
                            GridBagConstraints.BOTH,
                            new Insets(8, 8, 12, 8)));

            col++;

            if (col == columns) {
                col = 0;
                row++;
            }
        }

        addGlue();
    }

    private void createCards() {
        for (Quizable q : quizables) {
            if (q == null) {
                continue;
            }
            if (q.getName() == null || q.getName().isBlank()) {
                System.out.println("EMPTY NAME");
                System.out.println("  class = " + q.getClass().getName());
                System.out.println("  object = " + q);
                System.out.println("  id = " + System.identityHashCode(q));

                for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(q);

                        System.out.println("    "
                                + f.getName()
                                + " = "
                                + (v == null ? "null" : v.getClass().getSimpleName() + " : " + v));
                    } catch (Exception e) {
                        System.out.println("    " + f.getName() + " = <ERR>");
                    }
                }
            }

            QuizablePanel panel = buildQuizableCard(q);

            cards.add(panel);

            String name = q.getName();
            if (name != null && !name.isEmpty()) {
                cardsByName.putIfAbsent(name, panel);
            }
        }
        for (RawImageEntry entry : rawImageEntries) {
            JPanel holder = new JPanel(new GridBagLayout());

            holder.add(entry.imagePane,
                    GridBagUtils.gbc(
                            0, 0,
                            1.0, 1.0,
                            GridBagConstraints.CENTER,
                            GridBagConstraints.BOTH));

            tuneCardSize(holder);

            cards.add(holder);

            if (entry.title != null) {
                cardsByName.putIfAbsent(entry.title, holder);
            }
        }
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

        if (cardsPanel == null) {
            return;
        }

        if (context == null) {
            context = resolveContext();
        } else if (sharedContext != null) {
            sharedContext.addTopLevel(q);
        }

        QuizablePanel panel = buildQuizableCard(q);

        String name = q.getName();
        if (name != null && !name.isEmpty()) {
            cardsByName.putIfAbsent(name, panel);
        }

        addCardToGrid(panel);

        for (QuizablePanelTargetListener listener : targetListeners) {
            listener.quizablePanelsAdded(List.of(panel));
        }
    }

    /**
     * Re-renders the card backing {@code q} in place after its fields
     * changed, and notifies target listeners (the search panel) so they
     * re-index / re-sort it. No-op if {@code q} has no card yet. Call on
     * the EDT.
     */
    public void refreshQuizable(Quizable q) {
        if (q == null || cardsPanel == null) {
            return;
        }

        QuizablePanel card = findCard(q);

        if (card == null) {
            return;
        }

        card.refresh();

        cardsPanel.revalidate();
        cardsPanel.repaint();

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
        if (q == null || cardsPanel == null) {
            return;
        }

        if (findCard(q) != null) {
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
        for (JPanel card : cards) {
            if (card instanceof QuizablePanel qp && qp.getQuizable() == q) {
                return qp;
            }
        }
        return null;
    }

    private void addCardToGrid(JPanel card) {
        int index = cards.size();
        int col = index % columns;
        int row = index / columns;

        if (glueComponent != null) {
            cardsPanel.remove(glueComponent);
        }

        cardsPanel.add(card,
                GridBagUtils.gbc(
                        col, row,
                        1.0, 0.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.BOTH,
                        new Insets(8, 8, 12, 8)));

        cards.add(card);

        addGlue();

        cardsPanel.revalidate();
        cardsPanel.repaint();
    }

    private void addGlue() {
        int lastRow =
                cards.isEmpty() ? 0 : (cards.size() - 1) / columns + 1;

        if (glueComponent == null) {
            glueComponent = Box.createGlue();
        }

        GridBagConstraints glue = GridBagUtils.gbc(
                0, lastRow,
                1.0, 1.0,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.BOTH);

        glue.gridwidth = columns;
        cardsPanel.add(glueComponent, glue);
    }

    public JPanel getCardsPanel() {
        return cardsPanel;
    }

    public JScrollPane getCardsScrollPane() {
        return cardsScrollPane;
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
        if (containsImagePane(panel)) {
            Dimension pref = panel.getPreferredSize();

            int w = Math.max(pref.width, 260);
            int h = Math.max(pref.height, 260);

            panel.setPreferredSize(new Dimension(w, h));
            panel.setMinimumSize(new Dimension(220, 220));
        }
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
        if (cardsPanel == null) {
            createCardsPanel(numColumns);
        }
        frame = new JFrame(
                cards.size() == 1
                        ? title
                        : (title + ", " + cards.size()));

        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(6, 6));

        if (!quizables.isEmpty()) {
            Quizable first = quizables.getFirst();

            QuizableSearchPanel searchPanel =
                    new QuizableSearchPanel(first.getClass());

            searchPanel.setTarget(cardsPanel, cardsScrollPane);
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