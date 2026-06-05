package quiz.ui;

import aux.GridBagUtils;
import benchmark.generated.OscarNominationFormView;
import nobel.NobelPrize;
import oscar.OscarNomination;
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

    public QuizablePanelView() {
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

        cardsPanel = new JPanel(new GridBagLayout());
        cardsScrollPane = new JScrollPane(cardsPanel);

        cardsScrollPane.setDoubleBuffered(true);
        cardsScrollPane.getVerticalScrollBar().setUnitIncrement(20);
        cardsScrollPane.getHorizontalScrollBar().setUnitIncrement(20);
        cardsScrollPane.getVerticalScrollBar().setBlockIncrement(20);

        RepaintManager.currentManager(cardsScrollPane)
                .setDoubleBufferingEnabled(true);

        createCards();

        if (cards.isEmpty()) {
            return;
        }

        int cols = Math.max(1, numColumns);
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

            if (col == cols) {
                col = 0;
                row++;
            }
        }

        GridBagConstraints glue = GridBagUtils.gbc(
                0, row + 1,
                1.0, 1.0,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.BOTH);

        glue.gridwidth = cols;

        cardsPanel.add(Box.createGlue(), glue);
    }

    private void createCards() {
        QuizableRenderContext context =
                new QuizableRenderContext(quizables);

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
            QuizablePanelConfig cfg =
                    QuizablePanelConfig.all(q.getClass())
                            .setAddListener(true)
                            .setThumb(true);

            context.putClassConfig(q.getClass(), cfg);
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