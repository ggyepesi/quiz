package quiz.ui;

import quiz.pairing.PairingModel;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Swing adapter for {@link PairingModel}. The model owns interaction rules;
 * this class maps its states and match identities to SelectableCard styling
 * and reports completion to the enclosing round shell.
 */
public class PairingManager {

    private final PairingModel<SelectableCard, SelectableCard> model;
    private final Set<SelectableCard> leftCards = new LinkedHashSet<>();
    private final Set<SelectableCard> rightCards = new LinkedHashSet<>();
    private Consumer<Boolean> onSolvedChanged = ignored -> {};

    private static final Color[] COLORS = {
            Color.RED,
            Color.BLUE,
            Color.GREEN.darker(),
            Color.MAGENTA,
            Color.ORANGE,
            Color.CYAN.darker(),
            new Color(200, 100, 0)
    };

    public PairingManager(int expectedPairs) {
        model = new PairingModel<>(expectedPairs);
    }

    public void onSolvedChanged(Consumer<Boolean> callback) {
        onSolvedChanged = callback == null ? ignored -> {} : callback;
        onSolvedChanged.accept(model.isSolved());
    }

    public void registerPanel(SelectableCard panel, boolean isLeft) {
        if (panel == null) {
            return;
        }

        (isLeft ? leftCards : rightCards).add(panel);
        panel.setState(CardSelectionState.IDLE);
        panel.onSelected(ignored -> processClick(panel, isLeft));
        render();
    }

    private void processClick(SelectableCard clicked, boolean isLeft) {
        if (model.isSolved()) {
            return;
        }

        if (isLeft) {
            model.chooseLeft(clicked);
        } else {
            model.chooseRight(clicked);
        }

        render();
        repaintParent(clicked);
    }

    private void render() {
        for (SelectableCard card : leftCards) {
            render(card, model.leftState(card), model.leftMatch(card).orElse(null));
        }
        for (SelectableCard card : rightCards) {
            render(card, model.rightState(card), model.rightMatch(card).orElse(null));
        }
        onSolvedChanged.accept(model.isSolved());
    }

    private static void render(
            SelectableCard card,
            PairingModel.ChoiceState state,
            PairingModel.Match<SelectableCard, SelectableCard> match) {
        switch (state) {
            case IDLE -> card.setState(CardSelectionState.IDLE);
            case SELECTED -> card.setState(CardSelectionState.SELECTED);
            case MATCHED -> {
                card.setMatchColor(colorFor(match.number()));
                card.setState(CardSelectionState.MATCHED);
            }
        }
    }

    private static Color colorFor(int matchNumber) {
        return COLORS[(matchNumber - 1) % COLORS.length];
    }

    private void repaintParent(SelectableCard p) {
        SwingUtilities.invokeLater(() -> {
            Container parent = p.getParent();

            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }

            p.revalidate();
            p.repaint();
        });
    }
}
