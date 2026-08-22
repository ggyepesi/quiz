package quiz;

import objectview.Viewable;
import objectview.render.Card;

import objectview.viewconfig.ViewConfig;
import quiz.data.ViewableKeyExtractor;
import quiz.ui.QuizCardFactory;
import quiz.ui.QuizCardRole;

import java.awt.*;
import java.awt.event.MouseListener;
import java.util.*;
import java.util.List;
import javax.swing.*;
import quiz.group.ViewableGroup;

/**
 * Base quiz class — manages shared indexing, exhaustion counters, and UI utilities.
 */
public abstract class Quiz extends Thread {
    protected final ViewConfig queryConfig;
    protected final ViewConfig answerConfig;
    protected final ViewableGroup group;
    protected final Map<String, ? extends Viewable> viewables;

    protected final Map<List<Object>, List<List<Object>>> answersToQuery = new LinkedHashMap<>();
    protected final Map<List<Object>, Viewable> queryViewables = new LinkedHashMap<>();
    protected final Map<List<Object>, Viewable> answerViewables = new LinkedHashMap<>();
    protected final ViewableKeyExtractor keyExtractor = new ViewableKeyExtractor();
    protected final QuizCardFactory cardFactory;

    protected final Random random = new Random();
    protected volatile boolean stopped = false;

    protected JFrame frame;
    protected JComponent queryComponent;
    protected JComponent answerComponent;

    /** exhaustion tracking */
    protected final Map<List<Object>, Integer> correctAnswerUseCount = new HashMap<>();
    protected final Map<List<Object>, Integer> exhaustionUsage = new HashMap<>();
    protected final Set<List<Object>> exhaustedAnswers = new HashSet<>();

    protected int correctSelections = 0;
    protected int wrongSelections = 0;

    protected long startTimeMillis;
    protected long endTimeMillis;

    public Quiz(ViewConfig queryConfig,
                ViewConfig answerConfig,
                ViewableGroup group,
                Map<String, ? extends Viewable> viewables) {
        this.queryConfig = queryConfig == null ? new ViewConfig() : queryConfig;
        this.answerConfig = answerConfig == null ? new ViewConfig() : answerConfig;

        // Quiz images hide their answer (mask/OCR) wherever they appear — query
        // or answer. No-op unless an image has a mask or is an OCR-blur type.
        this.queryConfig.setBlurImages(true);
        this.answerConfig.setBlurImages(true);

        this.group = group;
        this.viewables = viewables == null ? Collections.emptyMap() : viewables;
        this.cardFactory = new QuizCardFactory(this.viewables.values());

        this.frame = new JFrame(getClass().getSimpleName());
        this.frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.frame.setSize(1400, 900);
        this.frame.setLocationRelativeTo(null);

        indexViewables();
    }

    public String prepareQuiz() {
        if (group.getMembers().size() < 2) {
            return "Quiz needs at least 2 Viewable items.";
        }
        return null;
    }

    public void show() {
        if (frame != null) start();
    }

    public long getElapsedMillis() {
        long end = endTimeMillis == 0
                ? System.currentTimeMillis()
                : endTimeMillis;

        return Math.max(0, end - startTimeMillis);
    }

    public double getElapsedSeconds() {
        return getElapsedMillis() / 1000.0;
    }

    public int getCorrectSelections() {
        return correctSelections;
    }

    public int getWrongSelections() {
        return wrongSelections;
    }

    public int getTotalTrials() {
        return correctSelections + wrongSelections;
    }

    protected void markCorrectTrial() {
        correctSelections++;
    }

    protected void markWrongTrial() {
        wrongSelections++;
    }

    protected void startTiming() {
        startTimeMillis = System.currentTimeMillis();
        endTimeMillis = 0;
    }

    protected void stopTiming() {
        endTimeMillis = System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Indexing and exhaustion count setup
    // -------------------------------------------------------------------------

    protected void indexViewables() {
        for (Viewable viewable : viewables.values()) {
            if (viewable == null) continue;
            if (!isInSelectedGroup(viewable)) continue;

            List<List<Object>> queryKeys = keyExtractor.combinations(viewable, queryConfig);
            List<List<Object>> answerKeys = keyExtractor.combinations(viewable, answerConfig);
            if (queryKeys.isEmpty() || answerKeys.isEmpty()) continue;

            for (List<Object> qk : queryKeys) {
                for (List<Object> ak : answerKeys) {
                    answersToQuery.computeIfAbsent(qk, k -> new ArrayList<>()).add(ak);
                    queryViewables.putIfAbsent(qk, viewable);
                    answerViewables.putIfAbsent(ak, viewable);
                    // count occurrences of correct usage
                    correctAnswerUseCount.merge(ak, 1, Integer::sum);
                }
            }
        }
    }

    private boolean isInSelectedGroup(Viewable viewable) {
        if (group == null) return true;
        try { return group.contains(viewable.getIdentifier()); }
        catch (Exception ignored) { return true; }
    }

    protected String safeName(Viewable q) {
        String name = q == null ? null : q.getName();
        return name == null ? "" : name;
    }

    // -------------------------------------------------------------------------
    // Exhaustion helpers
    // -------------------------------------------------------------------------

    protected void markAnswerAsUsed(Viewable choice) {
        for (Map.Entry<List<Object>, Viewable> e : answerViewables.entrySet()) {
            if (e.getValue().equals(choice)) {
                List<Object> key = e.getKey();
                int used = exhaustionUsage.getOrDefault(key, 0) + 1;
                exhaustionUsage.put(key, used);
                int allowed = correctAnswerUseCount.getOrDefault(key, 1);
                if (used >= allowed) exhaustedAnswers.add(key);
            }
        }
    }

    protected boolean isExhausted(List<Object> answerKey) {
        return exhaustedAnswers.contains(answerKey);
    }

    // -------------------------------------------------------------------------
    // Utility for consistent borders & listeners
    // -------------------------------------------------------------------------

    public static GridBagConstraints createGridBagConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 0.0; gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        return gbc;
    }

    public static void addMouseListenerRecursively(Component c, MouseListener l) {
        c.addMouseListener(l);
        if (c instanceof Container co) {
            for (Component child : co.getComponents()) addMouseListenerRecursively(child, l);
        }
    }

    protected void setGrayBorder(JPanel c) {
        c.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2, true));
    }

    /**
     * Ensures the configuration has the correct root class for the given Viewable.
     */
    protected ViewConfig withRootClass(ViewConfig cfg, Viewable q) {
        if (cfg == null) {
            return (q == null)
                    ? new ViewConfig()
                    : ViewConfig.of(q.getClass());
        }
        if (q == null || cfg.getCls() != null) {
            // config already defines a root class or viewable is null
            return cfg;
        }
        // clone config so changes don't leak elsewhere
        return cfg.copy().setCls(q.getClass());
    }

    /**
     * Builds a Card for the current question (query side).
     * Uses the queryConfig and forces full‑size images.
     */
    protected Card createQueryPanel(Viewable viewable) {
        return cardFactory.create(viewable, queryConfig, QuizCardRole.PROMPT);
    }
}
