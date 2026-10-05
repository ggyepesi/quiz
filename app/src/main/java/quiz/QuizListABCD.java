package quiz;

import objectview.Viewable;
import objectview.viewconfig.ViewConfig;
import quiz.model.QuizMode;
import quiz.data.FixedChoiceOptions;
import quiz.round.RoundProgress;
import quiz.ui.AnswerPanelFactory;
import quiz.ui.CardSelectionState;
import quiz.ui.SelectableCard;
import quiz.ui.ChoiceBoard;
import quiz.ui.ChoiceBoardPolicy;
import quiz.ui.RoundShell;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import objectview.group.ViewableGroup;

public class QuizListABCD extends Quiz {

    private final QuizMode mode;
    private final AnswerPanelFactory panelFactory;
    private List<List<Object>> shuffledKeys;
    private RoundProgress roundProgress;
    private final RoundShell roundShell = new RoundShell();

    public QuizListABCD(ViewConfig queryConfig,
                        ViewConfig answerConfig,
                        QuizAnswerType answerType,
                        ViewableGroup<?> group,
                        Map<String, ? extends Viewable> viewables) {
        super(queryConfig, answerConfig, group, viewables);
        this.mode = (answerType == QuizAnswerType.LIST)
                ? QuizMode.LIST
                : QuizMode.ABCD;
        this.panelFactory = new AnswerPanelFactory(answerConfig, cardFactory);
    }

    @Override
    public void run() {
        shuffledKeys = new ArrayList<>(answersToQuery.keySet());
        Collections.shuffle(shuffledKeys, random);
        roundProgress = new RoundProgress(shuffledKeys.size());
        startTiming();

        SwingUtilities.invokeLater(() -> {
            frame.setContentPane(roundShell);
            drawNextRound();
            frame.setVisible(true);
        });
    }

    private void drawNextRound() {
        if (stopped) return;
        if (roundProgress.isComplete()) {
            showCompletion();
            return;
        }

        JPanel roundContent = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGridBagConstraints();

        List<Object> questionKey = shuffledKeys.get(roundProgress.currentIndex());
        if (!queryContents.containsKey(questionKey)) {
            roundProgress.advance();
            drawNextRound();
            return;
        }

        // --- 1️⃣ QUESTION ------------------------------------------------------
        queryComponent = createQueryPanel(questionKey);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = (mode == QuizMode.LIST ? 0.25 : 0.35);
        roundContent.add(queryComponent, gbc);

        // --- 2️⃣ ANSWER SECTION -----------------------------------------------
        gbc.gridy++;
        gbc.weighty = (mode == QuizMode.LIST ? 0.65 : 0.55);

        if (mode == QuizMode.LIST) {
            // -------- LIST mode: show all items with 3 visual states --------
            List<List<Object>> allKeys = new ArrayList<>(answerViewables.keySet());
            Collections.shuffle(allKeys, random);
            List<ChoiceBoard.CardItem> choices = new ArrayList<>();
            List<List<Object>> choiceKeys = new ArrayList<>();
            for (List<Object> key : allKeys) {
                ChoiceBoard.CardItem choice = answerCardItem(key);
                if (choice != null) {
                    choices.add(choice);
                    choiceKeys.add(key);
                }
            }
            ChoiceBoard panel = ChoiceBoard.forCardItems(
                    choices, cardFactory, ChoiceBoardPolicy.answers(2),
                    choices.stream().map(ChoiceBoard.CardItem::content).toList());
            for (int index = 0; index < choiceKeys.size(); index++) {
                if (exhaustedAnswers.contains(choiceKeys.get(index))) {
                    panel.setState(index, CardSelectionState.EXHAUSTED);
                }
            }
            panel.onChoice(choice -> {
                if (!isCorrectChoice(questionKey, choice.item())) return;
                markAnswerAsUsed(answerKeyOf(choice.item()));
                choice.card().setState(CardSelectionState.CORRECT);
                roundShell.setAdvanceEnabled(true);
            });

            answerComponent = new JScrollPane(panel);
            roundContent.add(answerComponent, gbc);

        } else {
            // -------- ABCD mode: 4 randomized options --------
            List<List<Object>> optionKeys = buildAnswerOptionKeys(questionKey);
            List<ChoiceBoard.CardItem> quizOptions = optionKeys.stream()
                    .map(this::answerCardItem)
                    .filter(Objects::nonNull)
                    .toList();

            JPanel answersPanel = panelFactory.createAnswerCardPanels(quizOptions, choice -> {
                boolean correct = isCorrectChoice(questionKey, choice);
                if (!correct) return;
                markAnswerAsUsed(answerKeyOf(choice));
                highlightSelection(choice);
                roundShell.setAdvanceEnabled(true);
            });

            answerComponent = new JScrollPane(answersPanel);
            roundContent.add(answerComponent, gbc);
        }

        roundShell.showRound(
                roundProgress.snapshot(),
                roundContent,
                () -> {
            roundProgress.advance();
            drawNextRound();
        });
    }
    // --- Helper logic ---

    /** The one option-construction path for both List and ABCD. The returned set is
     * final: rendering may mark choices but must not filter it afterwards, or a
     * fixed-size round silently changes cardinality as exhaustion advances. */
    List<Viewable> buildAnswerOptions(List<Object> questionKey) {
        return buildAnswerOptionKeys(questionKey).stream()
                .map(answerViewables::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<List<Object>> buildAnswerOptionKeys(List<Object> questionKey) {
        List<List<Object>> correctAnswers = answersToQuery.get(questionKey);
        if (correctAnswers == null || correctAnswers.isEmpty()) return List.of();

        List<List<Object>> candidateKeys;
        if (mode == QuizMode.LIST) {
            // keep all keys, even exhausted ones (we'll paint them differently)
            candidateKeys = new ArrayList<>(answerViewables.keySet());
            Collections.shuffle(candidateKeys, random);
        } else {
            candidateKeys = FixedChoiceOptions.choose(
                    correctAnswers, correctAnswers, answerViewables.keySet(), 4, random);
        }

        return candidateKeys;
    }

    /** Whether the chosen card's answer key is one of this question's correct keys.
     *  Neither its name nor its owning instance decides: two instances can share a
     *  name, and one instance can supply a correct answer and a distractor. */
    boolean isCorrectChoice(List<Object> questionKey, Viewable selected) {
        List<Object> chosen = answerKeyOf(selected);
        List<List<Object>> correctKeys = answersToQuery.get(questionKey);
        return chosen != null && correctKeys != null && correctKeys.contains(chosen);
    }

    private void highlightSelection(Viewable selected) {
        if (!(answerComponent instanceof JScrollPane scroll)) return;
        Component view = scroll.getViewport().getView();
        if (!(view instanceof Container container)) return;

        for (Component c : container.getComponents()) {
            if (c instanceof SelectableCard selectable) {
                boolean same = selectable.item() == selected;
                selectable.setState(same
                        ? CardSelectionState.CORRECT
                        : CardSelectionState.IDLE);
            }
        }
    }

    private void showCompletion() {
        stopTiming();
        roundShell.showCompletion("✅ Quiz completed!");
    }
}
