package quiz;

import objectview.Viewable;
import objectview.viewconfig.ViewConfig;
import quiz.model.QuizMode;
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
import quiz.group.ViewableGroup;

public class QuizListABCD extends Quiz {

    private final QuizMode mode;
    private final AnswerPanelFactory panelFactory;
    private List<List<Object>> shuffledKeys;
    private RoundProgress roundProgress;
    private final RoundShell roundShell = new RoundShell();

    public QuizListABCD(ViewConfig queryConfig,
                        ViewConfig answerConfig,
                        QuizAnswerType answerType,
                        ViewableGroup group,
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
        Viewable viewable = queryViewables.get(questionKey);
        if (viewable == null) {
            roundProgress.advance();
            drawNextRound();
            return;
        }

        // --- 1️⃣ QUESTION ------------------------------------------------------
        queryComponent = createQueryPanel(viewable);
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
            List<Viewable> choices = new ArrayList<>();
            List<List<Object>> choiceKeys = new ArrayList<>();
            for (List<Object> key : allKeys) {
                Viewable q = answerViewables.get(key);
                if (q != null) {
                    choices.add(q);
                    choiceKeys.add(key);
                }
            }
            ChoiceBoard panel = new ChoiceBoard(
                    choices, answerConfig, cardFactory,
                    ChoiceBoardPolicy.answers(2));
            for (int index = 0; index < choiceKeys.size(); index++) {
                if (exhaustedAnswers.contains(choiceKeys.get(index))) {
                    panel.setState(index, CardSelectionState.EXHAUSTED);
                }
            }
            panel.onChoice(choice -> {
                if (!isCorrectChoice(questionKey, choice.item())) return;
                markAnswerAsUsed(choice.item());
                choice.card().setState(CardSelectionState.CORRECT);
                roundShell.setAdvanceEnabled(true);
            });

            answerComponent = new JScrollPane(panel);
            roundContent.add(answerComponent, gbc);

        } else {
            // -------- ABCD mode: 4 randomized options --------
            List<List<Object>> corrects = answersToQuery.get(questionKey);
            List<List<Object>> keys = pickFourOptions(corrects);

            List<Viewable> quizOptions = new ArrayList<>();
            for (List<Object> k : keys) {
                if (exhaustedAnswers.contains(k)) continue;
                Viewable q = answerViewables.get(k);
                if (q != null) quizOptions.add(q);
            }

            JPanel answersPanel = panelFactory.createAnswerPanels(quizOptions, choice -> {
                boolean correct = isCorrectChoice(questionKey, choice);
                if (!correct) return;
                markAnswerAsUsed(choice);
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

    private List<Viewable> buildAnswerOptions(List<Object> questionKey) {
        List<List<Object>> correctAnswers = answersToQuery.get(questionKey);
        if (correctAnswers == null || correctAnswers.isEmpty()) return List.of();

        List<List<Object>> candidateKeys;
        if (mode == QuizMode.LIST) {
            // keep all keys, even exhausted ones (we'll paint them differently)
            candidateKeys = new ArrayList<>(answerViewables.keySet());
            Collections.shuffle(candidateKeys, random);
        } else {
            candidateKeys = pickFourOptions(correctAnswers);
        }

        List<Viewable> quizOptions = new ArrayList<>();
        for (List<Object> key : candidateKeys) {
            Viewable q = answerViewables.get(key);
            if (q != null) quizOptions.add(q);
        }
        return quizOptions;
    }

    private List<List<Object>> pickFourOptions(List<List<Object>> correctAnswers) {
        List<List<Object>> result = new ArrayList<>();
        List<Object> correct = correctAnswers.get(random.nextInt(correctAnswers.size()));
        result.add(correct);

        List<List<Object>> allKeys = new ArrayList<>(answerViewables.keySet());
        Collections.shuffle(allKeys, random);
        for (List<Object> key : allKeys) {
            if (correctAnswers.contains(key)) continue;
            result.add(key);
            if (result.size() >= 4) break;
        }

        Collections.shuffle(result, random);
        return result;
    }

    private boolean isCorrectChoice(List<Object> questionKey, Viewable selected) {
        List<List<Object>> correctKeys = answersToQuery.get(questionKey);
        if (correctKeys == null) return false;
        for (List<Object> key : correctKeys) {
            Viewable q = answerViewables.get(key);
            if (q != null && q.getName().equals(selected.getName())) return true;
        }
        return false;
    }

    private void highlightSelection(Viewable selected) {
        if (!(answerComponent instanceof JScrollPane scroll)) return;
        Component view = scroll.getViewport().getView();
        if (!(view instanceof Container container)) return;

        for (Component c : container.getComponents()) {
            if (c instanceof SelectableCard selectable) {
                boolean same = selectable.item().equals(selected);
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
