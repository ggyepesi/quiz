package quiz;

import objectview.Viewable;

import objectview.QuizablePanel;
import objectview.QuizableRenderContext;
import objectview.viewconfig.QuizablePanelConfig;

import java.awt.*;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * Base quiz class — manages shared indexing, exhaustion counters, and UI utilities.
 */
public abstract class Quiz extends Thread {
    protected final QuizablePanelConfig queryConfig;
    protected final QuizablePanelConfig answerConfig;
    protected final QuizableGroup group;
    protected final Map<String, ? extends Quizable> quizables;

    protected final Map<List<Object>, List<List<Object>>> answersToQuery = new LinkedHashMap<>();
    protected final Map<List<Object>, Quizable> queryQuizables = new LinkedHashMap<>();
    protected final Map<List<Object>, Quizable> answerQuizables = new LinkedHashMap<>();

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

    public Quiz(QuizablePanelConfig queryConfig,
                QuizablePanelConfig answerConfig,
                QuizableGroup group,
                Map<String, ? extends Quizable> quizables) {
        this.queryConfig = queryConfig == null ? new QuizablePanelConfig() : queryConfig;
        this.answerConfig = answerConfig == null ? new QuizablePanelConfig() : answerConfig;

        // Quiz images hide their answer (mask/OCR) wherever they appear — query
        // or answer. No-op unless an image has a mask or is an OCR-blur type.
        this.queryConfig.setBlurImages(true);
        this.answerConfig.setBlurImages(true);

        this.group = group;
        this.quizables = quizables == null ? Collections.emptyMap() : quizables;

        this.frame = new JFrame(getClass().getSimpleName());
        this.frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        this.frame.setSize(1400, 900);
        this.frame.setLocationRelativeTo(null);

        indexQuizables();
    }

    public String prepareQuiz() {
        if (group.getMembers().size() < 2) {
            return "Quiz needs at least 2 Quizable items.";
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

    protected void indexQuizables() {
        List<List<String>> queryPaths = getFillPaths(queryConfig);
        List<List<String>> answerPaths = getShowPaths(answerConfig);

        for (Quizable quizable : quizables.values()) {
            if (quizable == null) continue;
            if (!isInSelectedGroup(quizable)) continue;

            List<List<Object>> queryKeys = extractKeyCombinations(quizable, queryPaths);
            List<List<Object>> answerKeys = extractKeyCombinations(quizable, answerPaths);
            if (queryKeys.isEmpty() || answerKeys.isEmpty()) continue;

            for (List<Object> qk : queryKeys) {
                for (List<Object> ak : answerKeys) {
                    answersToQuery.computeIfAbsent(qk, k -> new ArrayList<>()).add(ak);
                    queryQuizables.putIfAbsent(qk, quizable);
                    answerQuizables.putIfAbsent(ak, quizable);
                    // count occurrences of correct usage
                    correctAnswerUseCount.merge(ak, 1, Integer::sum);
                }
            }
        }
    }

    private boolean isInSelectedGroup(Quizable quizable) {
        if (group == null) return true;
        try { return group.contains(quizable.getIdentifier()); }
        catch (Exception ignored) { return true; }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers (same as your original)
    // -------------------------------------------------------------------------
    protected static List<List<String>> getFillPaths(QuizablePanelConfig config) {
        List<List<String>> paths = new ArrayList<>();
        collectPaths(config, new ArrayList<>(), paths);
        return paths;
    }

    protected static List<List<String>> getShowPaths(QuizablePanelConfig config) {
        return getFillPaths(config);
    }

    private static void collectPaths(QuizablePanelConfig config,
                                     List<String> prefix, List<List<String>> out) {
        if (config == null) return;
        if (config.isAllFields()) {
            collectAllFieldPaths(config.getCls(), prefix, out);
            return;
        }

        for (Map.Entry<String, QuizablePanelConfig> e : config.getFields().entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(e.getKey());
            QuizablePanelConfig child = e.getValue();
            if (child == null || child.isAllFields() || child.getFields().isEmpty())
                out.add(path);
            else collectPaths(child, path, out);
        }
    }

    private static void collectAllFieldPaths(Class<? extends Viewable> cls,
                                             List<String> prefix,
                                             List<List<String>> out) {
        if (cls == null) return;
        for (Field f : QuizableAdapter.getAllFields(cls)) {
            if ("name".equals(f.getName())) continue;
            List<String> path = new ArrayList<>(prefix);
            path.add(f.getName());
            out.add(path);
        }
    }

    private List<List<Object>> extractKeyCombinations(Quizable quizable,
                                                      List<List<String>> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }

        List<List<Object>> alternativesPerPath = new ArrayList<>();

        for (List<String> path : paths) {
            List<Object> alternatives = extractPathAlternatives(
                    quizable,
                    path,
                    Collections.newSetFromMap(new IdentityHashMap<>()));

            alternatives.removeIf(this::isEmptyValue);

            if (alternatives.isEmpty()) {
                return List.of();
            }

            alternativesPerPath.add(alternatives);
        }

        List<List<Object>> out = new ArrayList<>();
        buildCartesianKeys(alternativesPerPath, 0, new ArrayList<>(), out);
        return out;
    }

    private List<Object> extractPathAlternatives(Object current,
                                                 List<String> path,
                                                 Set<Object> visited) {
        Object raw = extractPathValueRecursive(current, path, 0, visited);

        List<Object> out = new ArrayList<>();
        flattenAlternatives(raw, out);

        return out;
    }

    private Object extractPathValueRecursive(Object current,
                                             List<String> path,
                                             int index,
                                             Set<Object> visited) {
        if (current == null) {
            return null;
        }

        if (index >= path.size()) {
            return current;
        }

        switch (current) {
            case Collection<?> collection -> {
                List<Object> out = new ArrayList<>();

                for (Object item : collection) {
                    Object v = extractPathValueRecursive(item, path, index, visited);
                    Object summarized = summarizeExtracted(v);

                    if (!isEmptyValue(summarized)) {
                        out.add(summarized);
                    }
                }

                return out;
            }
            case Map<?, ?> map -> {
                Map<Object, Object> out = new LinkedHashMap<>();

                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object v = extractPathValueRecursive(e.getValue(), path, index, visited);
                    Object summarized = summarizeExtracted(v);

                    if (!isEmptyValue(summarized)) {
                        out.put(summarizeSimple(e.getKey()), summarized);
                    }
                }

                return out;
            }
            case Quizable q -> {
                if (visited.contains(q)) {
                    return safeName(q);
                }

                visited.add(q);

                try {
                    String part = path.get(index);

                    if ("name".equals(part)) {
                        return q.getName();
                    }

                    Field field = QuizableAdapter.getField(q.getClass(), part);
                    if (field == null) {
                        return null;
                    }

                    field.setAccessible(true);
                    Object next = field.get(q);

                    return extractPathValueRecursive(next, path, index + 1, visited);
                } catch (Exception e) {
                    return null;
                } finally {
                    visited.remove(q);
                }
            }
            default -> {
            }
        }

        return null;
    }

    private void flattenAlternatives(Object value, List<Object> out) {
        Object summarized = summarizeExtracted(value);

        if (isEmptyValue(summarized)) {
            return;
        }

        if (summarized instanceof Collection<?> collection) {
            for (Object item : collection) {
                flattenAlternatives(item, out);
            }
            return;
        }

        if (summarized instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object key = summarizeSimple(e.getKey());
                Object val = summarizeExtracted(e.getValue());

                if (!isEmptyValue(val)) {
                    out.add(key + " -> " + val);
                }
            }
            return;
        }

        out.add(summarized);
    }

    private Object summarizeExtracted(Object value) {
        switch (value) {
            case null -> {
                return null;
            }
            case Quizable q -> {
                return safeName(q);
            }
            case Collection<?> collection -> {
                List<Object> out = new ArrayList<>();

                for (Object item : collection) {
                    Object summarized = summarizeExtracted(item);
                    if (!isEmptyValue(summarized)) {
                        out.add(summarized);
                    }
                }

                return out;
            }
            case Map<?, ?> map -> {
                Map<Object, Object> out = new LinkedHashMap<>();

                for (Map.Entry<?, ?> e : map.entrySet()) {
                    Object summarized = summarizeExtracted(e.getValue());

                    if (!isEmptyValue(summarized)) {
                        out.put(summarizeSimple(e.getKey()), summarized);
                    }
                }

                return out;
            }
            default -> {
            }
        }

        return value;
    }

    private Object summarizeSimple(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Quizable q) {
            return safeName(q);
        }

        return value;
    }

    private boolean isEmptyValue(Object value) {
        switch (value) {
            case null -> {
                return true;
            }
            case CharSequence cs -> {
                return cs.toString().trim().isEmpty();
            }
            case Collection<?> collection -> {
                if (collection.isEmpty()) {
                    return true;
                }

                for (Object item : collection) {
                    if (!isEmptyValue(item)) {
                        return false;
                    }
                }

                return true;
            }
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    return true;
                }

                for (Object item : map.values()) {
                    if (!isEmptyValue(item)) {
                        return false;
                    }
                }

                return true;
            }
            default -> {
            }
        }

        return false;
    }

    private void buildCartesianKeys(List<List<Object>> lists, int index,
                                    List<Object> current, List<List<Object>> out) {
        if (index == lists.size()) { out.add(new ArrayList<>(current)); return; }
        for (Object v : lists.get(index)) {
            current.add(v);
            buildCartesianKeys(lists, index + 1, current, out);
            current.removeLast();
        }
    }

    protected String safeName(Quizable q) {
        String n = (q == null ? null : q.getName());
        return n == null ? "" : n;
    }

    // -------------------------------------------------------------------------
    // Exhaustion helpers
    // -------------------------------------------------------------------------

    protected void markAnswerAsUsed(Quizable choice) {
        for (Map.Entry<List<Object>, Quizable> e : answerQuizables.entrySet()) {
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
     * Ensures the configuration has the correct root class for the given Quizable.
     */
    protected QuizablePanelConfig withRootClass(QuizablePanelConfig cfg, Quizable q) {
        if (cfg == null) {
            return (q == null)
                    ? new QuizablePanelConfig()
                    : QuizablePanelConfig.of(q.getClass());
        }
        if (q == null || cfg.getCls() != null) {
            // config already defines a root class or quizable is null
            return cfg;
        }
        // clone config so changes don't leak elsewhere
        return cfg.copy().setCls(q.getClass());
    }

    /**
     * Builds a QuizablePanel for the current question (query side).
     * Uses the queryConfig and forces full‑size images.
     */
    protected QuizablePanel createQueryPanel(Quizable quizable) {
        QuizablePanelConfig cfg = withRootClass(queryConfig, quizable).copy();
        cfg = fullSizeImagesRecursively(cfg);
        cfg.setAddListener(false);

        Set<Object> visited = QuizablePanel.identitySetOf();
        Set<Object> ancestors = QuizablePanel.identitySetOf();

        QuizableRenderContext context =
                new QuizableRenderContext(quizables.values());

        if (quizable != null) {
            context.putClassConfig(quizable.getClass(), cfg);
        }

        return new QuizablePanel(
                visited,
                ancestors,
                context,
                true,
                quizable,
                cfg,
                true,
                new ArrayList<>());
    }

    /**
     * Creates a deep copy of the config with thumbnails disabled (full‑size images).
     */
    protected QuizablePanelConfig fullSizeImagesRecursively(QuizablePanelConfig cfg) {
        if (cfg == null) {
            return null;
        }
        QuizablePanelConfig copy = cfg.copy();
        setThumbRecursively(copy, false);
        return copy;
    }

    private void setThumbRecursively(QuizablePanelConfig cfg, boolean thumb) {
        if (cfg == null) return;
        cfg.setThumb(thumb);
        for (QuizablePanelConfig child : cfg.getFields().values()) {
            setThumbRecursively(child, thumb);
        }
    }
}
