package quiz;

import aux.GridBagUtils;
import objectview.Card;
import objectview.viewconfig.ViewConfig;
import quiz.model.QuizMode;
import quiz.ui.AnswerPanelFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

public class QuizListABCD extends Quiz {

    private final QuizMode mode;
    private final AnswerPanelFactory panelFactory;
    private List<List<Object>> shuffledKeys;
    private int currentIndex = 0;

    private JButton nextButton;

    public QuizListABCD(ViewConfig queryConfig,
                        ViewConfig answerConfig,
                        QuizAnswerType answerType,
                        QuizableGroup group,
                        Map<String, ? extends Quizable> quizables) {
        super(queryConfig, answerConfig, group, quizables);
        this.mode = (answerType == QuizAnswerType.LIST)
                ? QuizMode.LIST
                : QuizMode.ABCD;
        this.panelFactory = new AnswerPanelFactory(answerConfig);
    }

    @Override
    public void run() {
        shuffledKeys = new ArrayList<>(answersToQuery.keySet());
        Collections.shuffle(shuffledKeys, random);

        SwingUtilities.invokeLater(() -> {
            frame.getContentPane().removeAll();
            frame.setLayout(new GridBagLayout());
            drawNextRound();
            frame.setVisible(true);
        });
    }

    private void drawNextRound() {
        if (stopped) return;
        if (currentIndex >= shuffledKeys.size()) {
            showCompletion();
            return;
        }

        frame.getContentPane().removeAll();
        GridBagConstraints gbc = createGridBagConstraints();

        List<Object> questionKey = shuffledKeys.get(currentIndex);
        Quizable quizable = queryQuizables.get(questionKey);
        if (quizable == null) {
            currentIndex++;
            drawNextRound();
            return;
        }

        // --- 1️⃣ QUESTION ------------------------------------------------------
        queryComponent = createQueryPanel(quizable);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = (mode == QuizMode.LIST ? 0.25 : 0.35);
        frame.add(queryComponent, gbc);

        // --- 2️⃣ ANSWER SECTION -----------------------------------------------
        gbc.gridy++;
        gbc.weighty = (mode == QuizMode.LIST ? 0.65 : 0.55);

        if (mode == QuizMode.LIST) {
            // -------- LIST mode: show all items with 3 visual states --------
            JPanel panel = new JPanel(new GridBagLayout());
            int row = 0, col = 0;

            List<List<Object>> allKeys = new ArrayList<>(answerQuizables.keySet());
            Collections.shuffle(allKeys, random);

            for (List<Object> key : allKeys) {
                Quizable q = answerQuizables.get(key);
                if (q == null) continue;

                boolean exhausted = exhaustedAnswers.contains(key);
                ViewConfig cfg = answerConfig.copy();
                cfg.setThumb(true);
                Card qp = new Card(q, cfg, quizables.values(), false);

                if (exhausted) {
                    // 3️⃣ not‑selectable (exhausted)
                    qp.setOpaque(true);
                    qp.setBackground(new Color(230, 230, 230));
                    qp.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 3, true));
                    qp.repaint();
                } else {
                    // selectable (idle gray)
                    qp.setOpaque(true);
                    qp.setBackground(new Color(250, 250, 250));
                    qp.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2, true));

                    Quiz.addMouseListenerRecursively(qp, new MouseAdapter() {
                        private boolean selected = false;

                        @Override
                        public void mousePressed(MouseEvent e) {
                            boolean correct = isCorrectChoice(questionKey, q);
                            if (!correct || selected) return;

                            markAnswerAsUsed(q);
                            // 2️⃣ mark as selected (green)
                            qp.setBackground(new Color(170, 255, 170));
                            qp.setBorder(BorderFactory.createLineBorder(Color.GREEN.darker(), 3, true));
                            qp.repaint();
                            selected = true;

                            nextButton.setEnabled(true);
                        }
                    });
                }

                panel.add(qp,
                        GridBagUtils.gbc(col, row, 1.0, 1.0,
                                GridBagConstraints.CENTER, GridBagConstraints.BOTH,
                                new Insets(6, 6, 6, 6)));

                if (++col == 2) {
                    col = 0;
                    row++;
                }
            }

            answerComponent = new JScrollPane(panel);
            frame.add(answerComponent, gbc);

        } else {
            // -------- ABCD mode: 4 randomized options --------
            List<List<Object>> corrects = answersToQuery.get(questionKey);
            List<List<Object>> keys = pickFourOptions(corrects);

            List<Quizable> quizOptions = new ArrayList<>();
            for (List<Object> k : keys) {
                if (exhaustedAnswers.contains(k)) continue;
                Quizable q = answerQuizables.get(k);
                if (q != null) quizOptions.add(q);
            }

            JPanel answersPanel = panelFactory.createAnswerPanels(quizOptions, choice -> {
                boolean correct = isCorrectChoice(questionKey, choice);
                if (!correct) return;
                markAnswerAsUsed(choice);
                highlightSelection(choice);
                nextButton.setEnabled(true);
            });

            answerComponent = new JScrollPane(answersPanel);
            frame.add(answerComponent, gbc);
        }

        // --- 3️⃣ NEXT BUTTON ---------------------------------------------------
        gbc.gridy++;
        gbc.weighty = 0.05;
        nextButton = new JButton(currentIndex < shuffledKeys.size() - 1 ? "Next" : "Finish");
        nextButton.setEnabled(false);
        nextButton.addActionListener(e -> {
            currentIndex++;
            drawNextRound();
        });
        frame.add(nextButton, gbc);

        frame.revalidate();
        frame.repaint();
    }
    // --- Helper logic ---

    private List<Quizable> buildAnswerOptions(List<Object> questionKey) {
        List<List<Object>> correctAnswers = answersToQuery.get(questionKey);
        if (correctAnswers == null || correctAnswers.isEmpty()) return List.of();

        List<List<Object>> candidateKeys;
        if (mode == QuizMode.LIST) {
            // keep all keys, even exhausted ones (we'll paint them differently)
            candidateKeys = new ArrayList<>(answerQuizables.keySet());
            Collections.shuffle(candidateKeys, random);
        } else {
            candidateKeys = pickFourOptions(correctAnswers);
        }

        List<Quizable> quizOptions = new ArrayList<>();
        for (List<Object> key : candidateKeys) {
            Quizable q = answerQuizables.get(key);
            if (q != null) quizOptions.add(q);
        }
        return quizOptions;
    }

    private List<List<Object>> pickFourOptions(List<List<Object>> correctAnswers) {
        List<List<Object>> result = new ArrayList<>();
        List<Object> correct = correctAnswers.get(random.nextInt(correctAnswers.size()));
        result.add(correct);

        List<List<Object>> allKeys = new ArrayList<>(answerQuizables.keySet());
        Collections.shuffle(allKeys, random);
        for (List<Object> key : allKeys) {
            if (correctAnswers.contains(key)) continue;
            result.add(key);
            if (result.size() >= 4) break;
        }

        Collections.shuffle(result, random);
        return result;
    }

    private boolean isCorrectChoice(List<Object> questionKey, Quizable selected) {
        List<List<Object>> correctKeys = answersToQuery.get(questionKey);
        if (correctKeys == null) return false;
        for (List<Object> key : correctKeys) {
            Quizable q = answerQuizables.get(key);
            if (q != null && q.getName().equals(selected.getName())) return true;
        }
        return false;
    }

    private void highlightSelection(Quizable selected) {
        if (!(answerComponent instanceof JScrollPane scroll)) return;
        Component view = scroll.getViewport().getView();
        if (!(view instanceof Container container)) return;

        for (Component c : container.getComponents()) {
            if (c instanceof Card qp) {
                boolean same = qp.getViewable().equals(selected);
                qp.setOpaque(true);
                qp.setBackground(same ? new Color(170, 255, 170)      // green highlight
                        : new Color(250, 250, 250));    // normal background
                qp.repaint();
            }
        }
    }

    private void showCompletion() {
        frame.getContentPane().removeAll();
        JLabel doneLabel = new JLabel("✅ Quiz completed!", SwingConstants.CENTER);
        doneLabel.setFont(new Font("Arial", Font.BOLD, 28));
        frame.add(doneLabel,
                GridBagUtils.gbc(0, 0, 1.0, 1.0,
                        GridBagConstraints.CENTER,
                        GridBagConstraints.BOTH,
                        new Insets(20, 20, 20, 20)));
        frame.revalidate();
        frame.repaint();
    }
}
