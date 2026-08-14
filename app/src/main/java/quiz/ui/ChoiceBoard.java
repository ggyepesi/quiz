package quiz.ui;

import objectview.utils.swing.GridBagUtils;
import objectview.render.Card;
import objectview.viewconfig.ViewConfig;
import objectview.Viewable;

import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable desktop grid of selectable quiz cards. It owns construction and
 * layout; game-specific correctness, exhaustion, and scoring remain callbacks.
 */
public final class ChoiceBoard extends JPanel {
    public record Choice(int index, Viewable item, SelectableCard card) {}

    private final ChoiceBoardPolicy policy;
    private final List<Choice> choices = new ArrayList<>();
    private Consumer<Choice> onChoice = ignored -> {};

    public ChoiceBoard(
            List<? extends Viewable> items,
            ViewConfig cardConfig,
            QuizCardFactory cardFactory,
            ChoiceBoardPolicy policy) {
        this(items, cardConfig, cardFactory, policy, null);
    }

    public ChoiceBoard(
            List<? extends Viewable> items,
            ViewConfig cardConfig,
            QuizCardFactory cardFactory,
            ChoiceBoardPolicy policy,
            Collection<? extends Viewable> localRenderContext) {
        if (cardFactory == null || policy == null) {
            throw new IllegalArgumentException(
                    "ChoiceBoard needs a card factory and policy");
        }
        this.policy = policy;
        setLayout(new GridBagLayout());

        int index = 0;
        for (Viewable item : items == null ? List.<Viewable>of() : items) {
            if (item == null) continue;
            Card content = cardFactory.create(
                    item, cardConfig, policy.cardRole(), localRenderContext);
            SelectableCard selectable =
                    new SelectableCard(item, content, policy.framedIdle());
            Choice choice = new Choice(index++, item, selectable);
            choices.add(choice);
            selectable.onSelected(ignored -> select(choice));

            int position = choices.size() - 1;
            int col = position % policy.columns();
            int row = position / policy.columns();
            add(selectable, GridBagUtils.weighted(
                    col, row, 1.0, 1.0,
                    GridBagConstraints.CENTER,
                    GridBagConstraints.BOTH,
                    new Insets(6, 6, 6, 6)));
        }
    }

    public List<Choice> choices() {
        return List.copyOf(choices);
    }

    public SelectableCard cardAt(int index) {
        return choices.get(index).card();
    }

    public void onChoice(Consumer<Choice> callback) {
        onChoice = callback == null ? ignored -> {} : callback;
    }

    public void onSelected(Consumer<Viewable> callback) {
        onChoice(callback == null
                ? null : choice -> callback.accept(choice.item()));
    }

    public void setState(int index, CardSelectionState state) {
        cardAt(index).setState(state);
    }

    private void select(Choice choice) {
        switch (policy.selectionMode()) {
            case EXTERNAL -> {
                // The quiz evaluator decides whether this click changes state.
            }
            case SINGLE -> {
                for (Choice other : choices) {
                    if (other.card().state() == CardSelectionState.SELECTED) {
                        other.card().setState(CardSelectionState.IDLE);
                    }
                }
                choice.card().setState(CardSelectionState.SELECTED);
            }
            case MULTIPLE -> choice.card().setState(
                    choice.card().state() == CardSelectionState.SELECTED
                            ? CardSelectionState.IDLE
                            : CardSelectionState.SELECTED);
        }
        onChoice.accept(choice);
    }
}
