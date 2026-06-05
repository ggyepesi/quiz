package wikidata.explore.qid;

import aux.GridBagUtils;
import quiz.Quizable;
import quiz.QuizablePanelConfig;
import quiz.ui.QuizablePanel;
import quiz.ui.QuizableRenderContext;
import quiz.ui.QuizableSearchPanel;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SearchableQuizableResultPanel<T extends Quizable>
        extends JPanel {

    private static final Color SELECTED_BORDER_COLOR =
            new Color(255, 140, 0);

    private final Class<T> cls;
    private final QuizablePanelConfig cardConfig;

    private final JPanel cardsPanel =
            new JPanel(new GridBagLayout());

    private final JScrollPane cardsScrollPane =
            new JScrollPane(cardsPanel);

    private final QuizableSearchPanel searchPanel;

    private final JButton detailsButton =
            new JButton("Details...");

    private final JButton openButton =
            new JButton("Open selected");

    private final JButton useButton =
            new JButton("Use selected");

    private final JLabel statusLabel =
            new JLabel(" ");

    private final List<T> rows =
            new ArrayList<>();

    private T selected;
    private QuizablePanel selectedPanel;
    private Border selectedOldBorder;

    private java.util.function.Consumer<T> detailsAction =
            v -> {};

    private java.util.function.Consumer<T> openAction =
            v -> {};

    private java.util.function.Consumer<T> useAction =
            v -> {};

    private boolean detailsOpening;

    public SearchableQuizableResultPanel(
            Class<T> cls,
            QuizablePanelConfig cardConfig) {

        super(new BorderLayout(4, 4));

        this.cls = cls;
        this.cardConfig = cardConfig;
        this.searchPanel = new QuizableSearchPanel(cls);

        buildUi();
        wireActions();
    }

    public void setDetailsAction(java.util.function.Consumer<T> detailsAction) {
        this.detailsAction =
                detailsAction == null ? v -> {} : detailsAction;
    }

    public void setOpenAction(java.util.function.Consumer<T> openAction) {
        this.openAction =
                openAction == null ? v -> {} : openAction;
    }

    public void setUseAction(java.util.function.Consumer<T> useAction) {
        this.useAction =
                useAction == null ? v -> {} : useAction;
    }

    public T selected() {
        return selected;
    }

    public void setRows(List<T> newRows) {
        rows.clear();

        if (newRows != null) {
            rows.addAll(newRows);
        }

        rebuildCards();
        statusLabel.setText("Rows: " + rows.size());
    }

    public void setStatus(String status) {
        statusLabel.setText(status == null ? " " : status);
    }

    public void setUseButtonText(String text) {
        useButton.setText(text == null || text.isBlank()
                ? "Use selected"
                : text);
    }

    public void setOpenButtonText(String text) {
        openButton.setText(text == null || text.isBlank()
                ? "Open selected"
                : text);
    }

    public void setDetailsButtonText(String text) {
        detailsButton.setText(text == null || text.isBlank()
                ? "Details..."
                : text);
    }

    public void setActionsEnabled(boolean enabled) {
        detailsButton.setEnabled(enabled);
        openButton.setEnabled(enabled);
        useButton.setEnabled(enabled);
    }

    private void buildUi() {
        JPanel actions =
                new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));

        actions.add(detailsButton);
        actions.add(openButton);
        actions.add(useButton);

        add(actions, BorderLayout.NORTH);

        cardsScrollPane.getVerticalScrollBar().setUnitIncrement(18);

        JSplitPane split =
                new JSplitPane(
                        JSplitPane.VERTICAL_SPLIT,
                        searchPanel,
                        cardsScrollPane);

        split.setResizeWeight(0.28);

        add(split, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private void wireActions() {
        detailsButton.addActionListener(e -> runDetailsAction());

        openButton.addActionListener(e -> {
            if (selected != null) {
                openAction.accept(selected);
            }
        });

        useButton.addActionListener(e -> {
            if (selected != null) {
                useAction.accept(selected);
            }
        });
    }

    private void rebuildCards() {
        selected = null;
        selectedPanel = null;
        selectedOldBorder = null;

        cardsPanel.removeAll();

        List<Quizable> quizables =
                new ArrayList<>(rows);

        QuizableRenderContext context =
                new QuizableRenderContext(quizables);

        context.putClassConfig(cls, cardConfig);

        int row = 0;

        for (T result : rows) {
            QuizablePanel panel =
                    new QuizablePanel(
                            result,
                            cardConfig.copy(),
                            context,
                            false);

            context.registerTopLevel(result, panel);

            Border baseBorder =
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createEmptyBorder(3, 3, 3, 3),
                            BorderFactory.createLineBorder(Color.LIGHT_GRAY));

            panel.setBorder(baseBorder);

            installSelectionHandler(panel, panel, result);

            cardsPanel.add(
                    panel,
                    GridBagUtils.gbc(
                            0,
                            row++,
                            1.0,
                            0.0,
                            GridBagConstraints.NORTHWEST,
                            GridBagConstraints.HORIZONTAL,
                            new Insets(3, 3, 3, 3)));
        }

        cardsPanel.add(
                Box.createGlue(),
                GridBagUtils.gbc(
                        0,
                        row,
                        1.0,
                        1.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.BOTH));

        cardsPanel.revalidate();
        cardsPanel.repaint();

        searchPanel.setTarget(cardsPanel, cardsScrollPane);
    }

    private void installSelectionHandler(
            Component component,
            QuizablePanel cardPanel,
            T value) {

        component.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                select(value, cardPanel);

                if (e.getClickCount() == 2) {
                    runDetailsAction();
                }
            }
        });

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                installSelectionHandler(child, cardPanel, value);
            }
        }
    }

    private void select(T value, QuizablePanel panel) {
        if (selectedPanel != null && selectedOldBorder != null) {
            selectedPanel.setBorder(selectedOldBorder);
            selectedPanel.repaint();
        }

        selected = value;
        selectedPanel = panel;
        selectedOldBorder = panel.getBorder();

        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(SELECTED_BORDER_COLOR, 3),
                selectedOldBorder));

        panel.repaint();

        statusLabel.setText("Selected: " + value);
    }

    private void runDetailsAction() {
        if (selected == null || detailsOpening) {
            return;
        }

        detailsOpening = true;

        SwingUtilities.invokeLater(() -> {
            try {
                detailsAction.accept(selected);
            } finally {
                detailsOpening = false;
            }
        });
    }
}
