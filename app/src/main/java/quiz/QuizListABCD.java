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
import quiz.ui.SearchableChoiceBoard;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import objectview.group.ViewableGroup;

public class QuizListABCD extends Quiz {

    private static final double LIST_QUERY_WEIGHT = 0.18;
    private static final double LIST_ANSWER_WEIGHT = 0.82;

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
        this(queryConfig, answerConfig, answerType, group, viewables, false);
    }

    QuizListABCD(ViewConfig queryConfig,
                 ViewConfig answerConfig,
                 QuizAnswerType answerType,
                 ViewableGroup<?> group,
                 Map<String, ? extends Viewable> viewables,
                 boolean deferIndexing) {
        super(queryConfig, answerConfig, group, viewables, deferIndexing);
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

        List<Object> questionKey = shuffledKeys.get(roundProgress.currentIndex());
        if (!queryContents.containsKey(questionKey)) {
            roundProgress.advance();
            drawNextRound();
            return;
        }

        JComponent roundContent = createRoundContent(questionKey);
        if (roundContent == null) {
            roundProgress.advance();
            drawNextRound();
            return;
        }

        roundShell.showRound(
                roundProgress.snapshot(),
                roundContent,
                () -> {
            roundProgress.advance();
            drawNextRound();
        });
    }

    JComponent createRoundContent(List<Object> questionKey) {
        JPanel roundContent = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = createGridBagConstraints();

        // --- 1️⃣ QUESTION ------------------------------------------------------
        queryComponent = createQueryPanel(questionKey);
        if (queryComponent == null) return null;
        JScrollPane queryScroll = new JScrollPane(
                queryComponent,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        queryScroll.setBorder(BorderFactory.createEmptyBorder());
        queryScroll.setMinimumSize(new Dimension(0, 0));
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = (mode == QuizMode.LIST ? LIST_QUERY_WEIGHT : 0.35);
        roundContent.add(queryScroll, gbc);

        // --- 2️⃣ ANSWER SECTION -----------------------------------------------
        gbc.gridy++;
        gbc.weighty = (mode == QuizMode.LIST ? LIST_ANSWER_WEIGHT : 0.55);

        if (mode == QuizMode.LIST) {
            // -------- LIST mode: all answers through ObjectView search/sort/view --------
            answerComponent = createListAnswerBoard(questionKey);
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

        return roundContent;
    }
    // --- Helper logic ---

    SearchableChoiceBoard createListAnswerBoard(List<Object> questionKey) {
        List<List<Object>> choiceKeys = buildAnswerOptionKeys(questionKey);
        List<ChoiceBoard.CardItem> choices = choiceKeys.stream()
                .map(this::answerCardItem)
                .filter(Objects::nonNull)
                .toList();
        SearchableChoiceBoard board = new SearchableChoiceBoard(
                choices, cardFactory, ChoiceBoardPolicy.answers(2),
                choices.stream().map(ChoiceBoard.CardItem::content).toList(),
                answerConfig);
        for (int index = 0; index < choices.size(); index++) {
            if (exhaustedAnswers.contains(answerKeyOf(choices.get(index).source()))) {
                board.setState(index, CardSelectionState.EXHAUSTED);
            }
        }
        board.onChoice(choice -> {
            if (!isCorrectChoice(questionKey, choice.item())) return;
            markAnswerAsUsed(answerKeyOf(choice.item()));
            board.setState(choice.index(), CardSelectionState.CORRECT);
            roundShell.setAdvanceEnabled(true);
        });
        return board;
    }

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
