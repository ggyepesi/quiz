package quiz;

import objectview.Viewable;
import objectview.utils.swing.GridBagUtils;
import objectview.render.Card;
import objectview.viewconfig.ViewConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import quiz.group.ViewableGroup;

public class QuizCategorize extends Quiz {
    private static final String ASSIGNED_CATEGORY_LABEL = "quiz.categorize.assignedCategoryLabel";

    private static final int ITEMS_PER_ROUND = 8;

    private final ViewableGroup categoryRoot;

    private final List<CategoryItem> remainingItems = new ArrayList<>();
    private final Map<Card, CategoryItem> itemByPanel =
            new IdentityHashMap<>();

    private Card selectedPanel;
    private JButton nextButton;
    private int solvedInRound = 0;
    private int roundSize = 0;

    public QuizCategorize(ViewConfig queryConfig,
                          ViewableGroup categoryRoot,
                          Map<String, ? extends Viewable> viewables) {
        super(queryConfig, new ViewConfig(), categoryRoot, viewables);
        this.categoryRoot = categoryRoot;
    }

    @Override
    public void run() {
        startTiming();
        SwingUtilities.invokeLater(() -> {
            frame.getContentPane().removeAll();
            frame.setLayout(new GridBagLayout());
            drawRound();
            frame.setVisible(true);
        });
    }

    @Override
    public String prepareQuiz() {
        remainingItems.clear();
        System.out.println("VIEWABLES " + viewables.size() + ", " + categoryRoot.getName());
        for (ViewableGroup category : categoryRoot.getChildren()) {
            System.out.println("  " + category.getName() + ", " + category.getMembers().size());
            if (category.getMembers().size() == viewables.size()) continue;
            for (objectview.Viewable member : category.getMembers()) {
                if (member instanceof Viewable q) {
                    remainingItems.add(new CategoryItem(q.getName(), q, category));
                }
            }
        }

        if (remainingItems.isEmpty()) {
            return "Quiz needs a group more than one subgroup not " +
                    "containing " +
                    "all the viewable items.";
        }
        if (remainingItems.size() < 2) {
            return "Quiz needs at least Viewable items";
        }
        Collections.shuffle(remainingItems, random);
        return null;
    }

    private void drawRound() {
        if (stopped) {
            return;
        }

        itemByPanel.clear();
        selectedPanel = null;
        solvedInRound = 0;
        if (remainingItems.isEmpty()) {
            showDone();
            return;
        }

        List<CategoryItem> round = takeRoundItems();
        roundSize = round.size();

        if (round.isEmpty()) {
            showDone();
            return;
        }

        frame.getContentPane().removeAll();

        JPanel cardsPanel = buildCardsPanel(round);
        JPanel categoriesPanel = buildCategoriesPanel(round);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(cardsPanel),
                new JScrollPane(categoriesPanel));

        split.setResizeWeight(0.70);

        GridBagConstraints gbc = createGridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;

        frame.add(split, gbc);

        nextButton = new JButton(remainingItems.isEmpty() ? "Finish" : "Next");
        nextButton.setFont(new Font("Arial", Font.BOLD, 20));
        nextButton.setEnabled(false);
        nextButton.addActionListener(e -> drawRound());

        frame.add(nextButton,
                GridBagUtils.weighted(
                        0, 1,
                        1.0, 0.0,
                        GridBagConstraints.CENTER,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(16, 20, 16, 20)));

        frame.revalidate();
        frame.repaint();
    }

    private List<CategoryItem> takeRoundItems() {
        List<CategoryItem> round = new ArrayList<>();

        while (!remainingItems.isEmpty()
                && round.size() < ITEMS_PER_ROUND) {
            round.add(remainingItems.removeFirst());
        }

        return round;
    }

    private JPanel buildCardsPanel(List<CategoryItem> round) {
        JPanel panel = new JPanel(new GridBagLayout());

        int row = 0;
        int col = 0;

        for (CategoryItem item : round) {
            Card card = createQueryPanel(item.viewable);

            itemByPanel.put(card, item);

            card.setOpaque(true);
            card.setBackground(new Color(250, 250, 250));
            card.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2, true));

            addMouseListenerRecursively(card, new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    selectCard(card);
                }
            });

            panel.add(card,
                    GridBagUtils.weighted(
                            col, row,
                            1.0, 0.0,
                            GridBagConstraints.NORTHWEST,
                            GridBagConstraints.BOTH,
                            new Insets(8, 8, 8, 8)));

            if (++col == 2) {
                col = 0;
                row++;
            }
        }

        panel.add(Box.createVerticalGlue(),
                GridBagUtils.weighted(
                        0, row + 1,
                        1.0, 1.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.BOTH));

        return panel;
    }

    private JPanel buildCategoriesPanel(List<CategoryItem> round) {
        JPanel panel = new JPanel(new GridBagLayout());
        Set<ViewableGroup> categories = new TreeSet<>(
                Comparator.comparing(ViewableGroup::getName));

        for (CategoryItem item : round) {
            categories.add(item.category);
        }

        int row = 0;
        JLabel title = new JLabel("Categories");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));

        panel.add(title,
                GridBagUtils.weighted(
                        0, row++,
                        1.0, 0.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(8, 8, 16, 8)));

        for (ViewableGroup category : categories) {
            JButton button = new JButton(category.getName());
            button.setFont(button.getFont().deriveFont(Font.BOLD, 18f));
            button.setHorizontalAlignment(SwingConstants.LEFT);

            button.addActionListener(e -> chooseCategory(category));

            panel.add(button,
                    GridBagUtils.weighted(
                            0, row++,
                            1.0, 0.0,
                            GridBagConstraints.NORTHWEST,
                            GridBagConstraints.HORIZONTAL,
                            new Insets(6, 8, 6, 8)));
        }

        panel.add(Box.createVerticalGlue(),
                GridBagUtils.weighted(
                        0, row,
                        1.0, 1.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.BOTH));

        return panel;
    }

    private void selectCard(Card card) {
        if (!itemByPanel.containsKey(card)) {
            return;
        }

        if (selectedPanel != null && selectedPanel != card) {
            resetUnsolvedCard(selectedPanel);
        }

        selectedPanel = card;
        card.setBorder(BorderFactory.createLineBorder(Color.PINK, 4, true));
        card.repaint();
    }

    private void chooseCategory(ViewableGroup chosen) {
        if (selectedPanel == null) {
            return;
        }

        CategoryItem item = itemByPanel.get(selectedPanel);

        if (item == null) {
            return;
        }

        if (item.category == chosen
                || Objects.equals(item.category.getFullName(), chosen.getFullName())) {
            markCorrect(selectedPanel, chosen);
            itemByPanel.remove(selectedPanel);
            selectedPanel = null;
            solvedInRound++;

            if (solvedInRound >= roundSize) {
                nextButton.setEnabled(true);
            }
        } else {
            flashWrong(selectedPanel);
        }
    }

    private void markCorrect(Card panel, ViewableGroup category) {
        markCorrectTrial();
        panel.setOpaque(true);
        panel.setBackground(new Color(170, 255, 170));
        panel.setBorder(BorderFactory.createLineBorder(Color.GREEN.darker(), 4, true));

        JLabel label = new JLabel("✓ " + category.getName());
        label.setForeground(Color.GREEN.darker());
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));

        Object old = panel.getClientProperty(ASSIGNED_CATEGORY_LABEL);
        if (old instanceof JLabel oldLabel) {
            panel.remove(oldLabel);
        }

        panel.putClientProperty(ASSIGNED_CATEGORY_LABEL, label);

        panel.add(label, GridBagUtils.weighted(
                0,
                panel.getComponentCount(),
                1.0,
                0.0,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.HORIZONTAL,
                new Insets(6, 8, 4, 8)));

        panel.revalidate();
        panel.repaint();
    }

    private void flashWrong(Card panel) {
        markWrongTrial();
        panel.setOpaque(true);
        panel.setBackground(new Color(255, 190, 190));
        panel.setBorder(BorderFactory.createLineBorder(Color.RED, 4, true));
        panel.repaint();

        javax.swing.Timer timer = new javax.swing.Timer(450, e -> {
            if (panel == selectedPanel) {
                panel.setBackground(new Color(250, 250, 250));
                panel.setBorder(BorderFactory.createLineBorder(Color.PINK, 4, true));
                panel.repaint();
            }
        });

        timer.setRepeats(false);
        timer.start();
    }

    private void resetUnsolvedCard(Card panel) {
        if (!itemByPanel.containsKey(panel)) {
            return;
        }

        panel.setOpaque(true);
        panel.setBackground(new Color(250, 250, 250));
        panel.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2, true));
        panel.repaint();
    }

    private void showDone() {
        frame.getContentPane().removeAll();
        JLabel label = new JLabel("✅ Categorization completed!", SwingConstants.CENTER);
        label.setFont(new Font("Arial", Font.BOLD, 28));
        stopTiming();
        frame.add(label,
                GridBagUtils.weighted(
                        0, 0,
                        1.0, 1.0,
                        GridBagConstraints.CENTER,
                        GridBagConstraints.BOTH,
                        new Insets(20, 20, 20, 20)));
        frame.revalidate();
        frame.repaint();
    }

    private record CategoryItem(String key, Viewable viewable, ViewableGroup category) {
    }
}
