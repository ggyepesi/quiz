package quiz;

import objectview.Viewable;
import objectview.utils.swing.GridBagUtils;
import objectview.render.Card;
import quiz.round.RoundProgress;
import quiz.ui.PairingManager;
import quiz.ui.QuizCardRole;
import quiz.ui.RoundShell;
import quiz.ui.SelectableCard;
import objectview.viewconfig.ViewConfig;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import objectview.group.ViewableGroup;

/** Side‑by‑side pairing quiz with exhaustion awareness. */
public class QuizPairs extends Quiz {
    private static final int PAIRS_PER_ROUND = 4;

    private PairingManager pairingManager;
    private RoundProgress roundProgress;
    private final RoundShell roundShell = new RoundShell();

    public QuizPairs(ViewConfig queryConfig,
                     ViewConfig answerConfig,
                     ViewableGroup<?> group,
                     Map<String, ? extends Viewable> viewables) {
        super(queryConfig, answerConfig, group, viewables);
    }

    @Override
    public void run() {
        List<List<Object>> queries = new ArrayList<>(answersToQuery.keySet());
        Collections.shuffle(queries);
        int totalRounds =
                (queries.size() + PAIRS_PER_ROUND - 1) / PAIRS_PER_ROUND;
        roundProgress = new RoundProgress(totalRounds);
        startTiming();

        SwingUtilities.invokeLater(() -> {
            frame.setContentPane(roundShell);
            drawRound(queries);
            frame.setVisible(true);
        });
    }

    private void drawRound(List<List<Object>> remaining) {
        if (remaining.isEmpty() || roundProgress.isComplete()) {
            roundProgress.complete();
            showDone();
            return;
        }

        List<PairItem> pairs = createRoundData(remaining);
        if (pairs.isEmpty()) {
            roundProgress.complete();
            showDone();
            return;
        }

        pairingManager = new PairingManager(pairs.size());

        JPanel pairPanel = buildPairsPanel(pairs);
        JScrollPane scroll = new JScrollPane(pairPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        roundShell.showRound(
                roundProgress.snapshot(),
                scroll,
                remaining.isEmpty() ? "Finish" : "Next",
                () -> {
                    for (PairItem p : pairs) {
                        markAnswerAsUsed(p.answerKey);
                    }
                    roundProgress.advance();
                    drawRound(remaining);
                });
        pairingManager.onSolvedChanged(roundShell::setAdvanceEnabled);
    }

    private List<PairItem> createRoundData(List<List<Object>> queryKeys) {
        List<PairItem> pairs = new ArrayList<>();
        int limit = Math.min(PAIRS_PER_ROUND, queryKeys.size());

        while (!queryKeys.isEmpty() && pairs.size() < limit) {
            List<Object> qKey = queryKeys.remove(random.nextInt(queryKeys.size()));
            List<List<Object>> answers = answersToQuery.get(qKey);
            if (answers == null || answers.isEmpty()) continue;

            // One not-yet-exhausted answer, chosen uniformly in one pass. Copying the
            // available answers first cost the whole (possibly lazy Cartesian) answer
            // list for every pair.
            List<Object> aKey = quiz.data.FixedChoiceOptions.pickOne(
                    answers, answer -> !exhaustedAnswers.contains(answer), random);
            if (aKey == null) continue;
            // Pair selection/provenance stays on the source instance. Rendering
            // below independently uses the assembled content object for qKey.
            Viewable q = queryViewables.get(qKey);
            Viewable a = answerViewables.get(aKey);
            if (q == null || a == null) continue;

            pairs.add(new PairItem(qKey, aKey, q, a));
        }
        return pairs;
    }

    private JPanel buildPairsPanel(List<PairItem> data) {
        JPanel p = new JPanel(new GridBagLayout());
        int row = 0;
        for (PairItem item : data) {
            Card left = createQueryPanel(
                    item.queryKey, QuizCardRole.PAIR_PROMPT);
            Card right = createAnswerPanel(
                    item.answerKey, QuizCardRole.PAIR_ANSWER);
            SelectableCard leftChoice =
                    new SelectableCard(item.query, left, false);
            SelectableCard rightChoice =
                    new SelectableCard(item.answer, right, false);
            pairingManager.registerPanel(leftChoice, true);
            pairingManager.registerPanel(rightChoice, false);

            p.add(leftChoice, GridBagUtils.weighted(0, row, 0.5, 0.0,
                    GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(8,8,8,8)));
            p.add(rightChoice, GridBagUtils.weighted(1, row++, 0.5, 0.0,
                    GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(8,8,8,8)));
        }
        return p;
    }

    private void showDone() {
        stopTiming();
        roundShell.showCompletion("✅ All rounds completed!");
    }

    private static class PairItem {
        final List<Object> queryKey, answerKey;
        final Viewable query, answer;
        PairItem(List<Object> qk, List<Object> ak, Viewable q, Viewable a) {
            this.queryKey = qk; this.answerKey = ak; this.query = q; this.answer = a;
        }
    }
}
