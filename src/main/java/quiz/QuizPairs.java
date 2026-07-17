package quiz;

import aux.GridBagUtils;
import quiz.ui.PairingManager;
import objectview.QuizablePanel;
import objectview.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/** Side‑by‑side pairing quiz with exhaustion awareness. */
public class QuizPairs extends Quiz {

    private PairingManager pairingManager;

    public QuizPairs(QuizablePanelConfig queryConfig,
                     QuizablePanelConfig answerConfig,
                     QuizableGroup group,
                     Map<String, ? extends Quizable> quizables) {
        super(queryConfig, answerConfig, group, quizables);
    }

    @Override
    public void run() {
        List<List<Object>> queries = new ArrayList<>(answersToQuery.keySet());
        Collections.shuffle(queries);

        SwingUtilities.invokeLater(() -> {
            frame.getContentPane().removeAll();
            frame.setLayout(new GridBagLayout());
            drawRound(queries);
            frame.setVisible(true);
        });
    }

    private void drawRound(List<List<Object>> remaining) {
        if (remaining.isEmpty()) { showDone(); return; }

        List<PairItem> pairs = createRoundData(remaining);
        if (pairs.isEmpty()) { showDone(); return; }

        pairingManager = new PairingManager();
        pairingManager.setExpectedPairs(pairs.size());

        JPanel pairPanel = buildPairsPanel(pairs);
        JScrollPane scroll = new JScrollPane(pairPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        JButton next = pairingManager.getNextButton();
        next.addActionListener(e -> {
            for (PairItem p : pairs) markAnswerAsUsed(answerQuizables.get(p.answerKey));
            drawRound(remaining);
        });

        frame.getContentPane().removeAll();
        GridBagConstraints gbc = createGridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        frame.add(scroll, gbc);
        gbc.gridy++;
        gbc.weighty = 0.0;
        frame.add(next, GridBagUtils.gbc(0, gbc.gridy, 1.0, 0.0,
                GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                new Insets(16,20,16,20)));
        frame.revalidate(); frame.repaint();
    }

    private List<PairItem> createRoundData(List<List<Object>> queryKeys) {
        List<PairItem> pairs = new ArrayList<>();
        int limit = Math.min(4, queryKeys.size());

        while (!queryKeys.isEmpty() && pairs.size() < limit) {
            List<Object> qKey = queryKeys.remove(random.nextInt(queryKeys.size()));
            List<List<Object>> answers = answersToQuery.get(qKey);
            if (answers == null || answers.isEmpty()) continue;

            // filter exhausted
            List<List<Object>> available = new ArrayList<>();
            for (List<Object> a : answers)
                if (!exhaustedAnswers.contains(a)) available.add(a);
            if (available.isEmpty()) continue;

            List<Object> aKey = available.get(random.nextInt(available.size()));
            Quizable q = queryQuizables.get(qKey);
            Quizable a = answerQuizables.get(aKey);
            if (q == null || a == null) continue;

            pairs.add(new PairItem(qKey, aKey, q, a));
        }
        return pairs;
    }

    private JPanel buildPairsPanel(List<PairItem> data) {
        JPanel p = new JPanel(new GridBagLayout());
        int row = 0;
        for (PairItem item : data) {
            QuizablePanel left = new QuizablePanel(item.query, withRootClass(queryConfig, item.query), (Collection<? extends Quizable>) null, true);
            QuizablePanel right = new QuizablePanel(item.answer, withRootClass(answerConfig, item.answer), (Collection<? extends Quizable>) null, false);
            pairingManager.registerPanel(left, true);
            pairingManager.registerPanel(right, false);

            p.add(left, GridBagUtils.gbc(0, row, 0.5, 0.0,
                    GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(8,8,8,8)));
            p.add(right, GridBagUtils.gbc(1, row++, 0.5, 0.0,
                    GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(8,8,8,8)));
        }
        return p;
    }

    private void showDone() {
        frame.getContentPane().removeAll();
        JLabel label = new JLabel("✅ All rounds completed!", SwingConstants.CENTER);
        label.setFont(new Font("Arial", Font.BOLD, 28));
        frame.add(label, GridBagUtils.gbc(0, 0, 1.0, 1.0,
                GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(20,20,20,20)));
        frame.revalidate(); frame.repaint();
    }

    private static class PairItem {
        final List<Object> queryKey, answerKey;
        final Quizable query, answer;
        PairItem(List<Object> qk, List<Object> ak, Quizable q, Quizable a) {
            this.queryKey = qk; this.answerKey = ak; this.query = q; this.answer = a;
        }
    }
}
