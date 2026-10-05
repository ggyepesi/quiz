package quiz;

import objectview.Viewable;
import objectview.field.FieldPath;
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
import objectview.group.ViewableGroup;

/**
 * Base quiz class — manages shared indexing, exhaustion counters, and UI utilities.
 */
public abstract class Quiz extends Thread {
    protected final ViewConfig queryConfig;
    protected final ViewConfig answerConfig;
    protected final ViewableGroup<?> group;
    protected final Map<String, ? extends Viewable> viewables;

    protected final Map<List<Object>, List<List<Object>>> answersToQuery = new LinkedHashMap<>();
    protected final Map<List<Object>, Viewable> queryViewables = new LinkedHashMap<>();
    protected final Map<List<Object>, ViewableKeyExtractor.KeyContent> queryContents =
            new LinkedHashMap<>();
    protected final Map<List<Object>, Viewable> answerViewables = new LinkedHashMap<>();
    protected final Map<List<Object>, ViewableKeyExtractor.KeyContent> answerContents =
            new LinkedHashMap<>();
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
    /** The answer key each answer card stands for, by the card's assembled object.
     *  One instance can supply several answer keys that now render differently, so a
     *  card's instance cannot say which answer was chosen — its key does. */
    private final Map<Viewable, List<Object>> answerKeyByCard = new java.util.IdentityHashMap<>();

    /** Why the selected fields could not be indexed; reported by prepareQuiz. */
    private String indexProblem;

    protected int correctSelections = 0;
    protected int wrongSelections = 0;

    protected long startTimeMillis;
    protected long endTimeMillis;

    public Quiz(ViewConfig queryConfig,
                ViewConfig answerConfig,
                ViewableGroup<?> group,
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
        if (indexProblem != null) return indexProblem;
        int selectedItems = group == null
                ? viewables.size() : group.getMembers().size();
        if (selectedItems < 2) {
            return "Quiz needs at least 2 Viewable items.";
        }
        if (requiresDisjointQuestionAndAnswerFields()) {
            Viewable sample = viewables.values().stream()
                    .filter(Objects::nonNull)
                    .filter(this::isInSelectedGroup)
                    .findFirst().orElse(null);
            String overlap = disjointFieldProblem(
                    keyExtractor.paths(sample, queryConfig),
                    keyExtractor.paths(sample, answerConfig));
            if (overlap != null) return overlap;
        }
        if (answersToQuery.isEmpty()) {
            return "No quiz items have values for every selected query and answer field. "
                    + "Select fields that contain values in this class.";
        }
        if (queryViewables.size() < 2 || answerViewables.size() < 2) {
            return "Quiz needs at least 2 distinct usable query and answer values for "
                    + "the selected fields.";
        }
        return null;
    }

    /** List, ABCD and Pairing present independently authored question and answer
     * fields. Quiz kinds with one presentation config override this. */
    protected boolean requiresDisjointQuestionAndAnswerFields() {
        return true;
    }

    static String disjointFieldProblem(
            Collection<FieldPath> questionFields,
            Collection<FieldPath> answerFields) {
        if (questionFields == null || answerFields == null) return null;
        LinkedHashSet<FieldPath> overlaps = new LinkedHashSet<>(questionFields);
        overlaps.retainAll(new LinkedHashSet<>(answerFields));
        if (overlaps.isEmpty()) return null;
        String fields = overlaps.stream().map(FieldPath::dotted)
                .collect(java.util.stream.Collectors.joining(", "));
        return "Question and answer fields must be disjoint. Used on both sides: "
                + fields + ".";
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

    /** Indexes every selected instance, or none: a selection one instance cannot
     *  index is refused whole and named, rather than thrown from the constructor —
     *  which, run on the event thread, ended the quiz with only a stack trace. */
    protected void indexViewables() {
        try {
            indexEachViewable();
        } catch (ViewableKeyExtractor.TooManyCombinations tooMany) {
            answersToQuery.clear();
            queryViewables.clear();
            queryContents.clear();
            answerViewables.clear();
            answerContents.clear();
            correctAnswerUseCount.clear();
            answerKeyByCard.clear();
            indexProblem = tooMany.getMessage();
        }
    }

    private void indexEachViewable() {
        for (Viewable viewable : viewables.values()) {
            if (viewable == null) continue;
            if (!isInSelectedGroup(viewable)) continue;

            List<FieldPath> queryPaths = keyExtractor.paths(viewable, queryConfig);
            List<FieldPath> answerPaths = keyExtractor.paths(viewable, answerConfig);
            List<List<Object>> queryKeys = keyExtractor.combinations(viewable, queryPaths);
            List<List<Object>> answerKeys = keyExtractor.combinations(viewable, answerPaths);
            if (queryKeys.isEmpty() || answerKeys.isEmpty()) continue;

            // Every question key produced by one instance has the same correct answers.
            // Keep that list once and share it; appending every q x a pair made large,
            // multi-valued domains retain the Cartesian product as separate list entries.
            List<List<Object>> sharedAnswers = answerKeys;
            for (List<Object> qk : queryKeys) {
                List<List<Object>> previous = answersToQuery.putIfAbsent(
                        qk, sharedAnswers);
                if (previous != null) {
                    // Equal question values from different instances are one question
                    // with all of their answers. Expand only that actual collision.
                    CorrectAnswerBucket bucket;
                    if (previous instanceof CorrectAnswerBucket existing) {
                        bucket = existing;
                    } else {
                        bucket = new CorrectAnswerBucket(previous);
                        answersToQuery.put(qk, bucket);
                    }
                    bucket.add(sharedAnswers);
                }
                if (queryViewables.putIfAbsent(qk, viewable) == null) {
                    queryContents.put(qk, keyExtractor.contentObject(
                            viewable, queryConfig, queryPaths, qk));
                }
            }
            for (List<Object> ak : answerKeys) {
                if (answerViewables.putIfAbsent(ak, viewable) == null) {
                    ViewableKeyExtractor.KeyContent content = keyExtractor.contentObject(
                            viewable, answerConfig, answerPaths, ak);
                    answerContents.put(ak, content);
                    if (content != null) answerKeyByCard.put(content.object(), ak);
                }
                // The answer is correct once for every question alternative supplied
                // by this instance, without retaining those repeated pairs.
                correctAnswerUseCount.merge(ak, queryKeys.size(), Integer::sum);
            }
        }
    }

    /** Lazy concatenation used only when several instances have the same question
     * value. The ordinary case keeps sharing one lazy Cartesian answer view. */
    static final class CorrectAnswerBucket extends AbstractList<List<Object>> {
        private final List<List<List<Object>>> segments = new ArrayList<>();
        private int size;

        CorrectAnswerBucket(List<List<Object>> first) {
            add(first);
        }

        void add(List<List<Object>> segment) {
            if (segment == null || segment.isEmpty()) return;
            segments.add(segment);
            size = Math.addExact(size, segment.size());
        }

        @Override public int size() {
            return size;
        }

        @Override public List<Object> get(int index) {
            Objects.checkIndex(index, size);
            int remaining = index;
            for (List<List<Object>> segment : segments) {
                if (remaining < segment.size()) return segment.get(remaining);
                remaining -= segment.size();
            }
            throw new IndexOutOfBoundsException(index);
        }

        @Override public Iterator<List<Object>> iterator() {
            Iterator<List<List<Object>>> outer = segments.iterator();
            return new Iterator<>() {
                private Iterator<List<Object>> inner = Collections.emptyIterator();

                @Override public boolean hasNext() {
                    while (!inner.hasNext() && outer.hasNext()) {
                        inner = outer.next().iterator();
                    }
                    return inner.hasNext();
                }

                @Override public List<Object> next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return inner.next();
                }
            };
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

    /** Marks one chosen answer key as used once more. Only that key: another answer
     *  the same instance supplies is a different card and was not chosen. */
    protected void markAnswerAsUsed(List<Object> key) {
        if (key == null) return;
        int used = exhaustionUsage.getOrDefault(key, 0) + 1;
        exhaustionUsage.put(key, used);
        int allowed = correctAnswerUseCount.getOrDefault(key, 1);
        if (used >= allowed) exhaustedAnswers.add(key);
    }

    /** The answer key an answer card stands for; null for anything that is not one. */
    protected List<Object> answerKeyOf(Viewable card) {
        return card == null ? null : answerKeyByCard.get(card);
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

    void setFieldSchemaResolver(
            java.util.function.Function<Viewable, objectview.field.FieldSchema> resolver) {
        cardFactory.setFieldSchemaResolver(resolver);
    }

    /** Renders the exact object that supplied this selected query key with the
     * corresponding (possibly re-rooted) query ViewConfig. */
    protected Card createQueryPanel(List<Object> queryKey) {
        return createQueryPanel(queryKey, QuizCardRole.PROMPT);
    }

    protected Card createQueryPanel(List<Object> queryKey, QuizCardRole role) {
        ViewableKeyExtractor.KeyContent content = queryContents.get(queryKey);
        if (content == null) return null;
        return cardFactory.create(
                content.object(), content.viewConfig(), role);
    }

    protected Card createAnswerPanel(List<Object> answerKey, QuizCardRole role) {
        ViewableKeyExtractor.KeyContent content = answerContents.get(answerKey);
        if (content == null) return null;
        return cardFactory.create(
                content.object(), content.viewConfig(), role);
    }

    protected quiz.ui.ChoiceBoard.CardItem answerCardItem(
            List<Object> answerKey) {
        ViewableKeyExtractor.KeyContent content = answerContents.get(answerKey);
        if (content == null) return null;
        // The card's identity is its assembled object, which stands for exactly this
        // key; the owning instance stays available as provenance via answerViewables.
        return new quiz.ui.ChoiceBoard.CardItem(
                content.object(), content.object(), content.viewConfig());
    }
}
