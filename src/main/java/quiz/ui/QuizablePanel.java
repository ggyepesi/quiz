package quiz.ui;

import aux.GridBagUtils;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.ui.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

/**
 * Renders a {@link Quizable} as a card by reflecting over its fields.
 *
 * <h3>Field annotations (rendering hints)</h3>
 * <ul>
 *   <li><b>(none)</b> — scalar leaves (String/number/enum) fold into a
 *       shared, drag-selectable {@link QuizableTextBlock}. A nested
 *       {@link Quizable} value — whether a single field or a member of a
 *       collection/map, at any depth — renders as a <i>collapsed
 *       reference chip</i>.</li>
 *   <li>{@link quiz.annotations.QuizableInline @QuizableInline} — force the
 *       nested Quizable(s) to render fully expanded inline (recursively). Use
 *       only on small, bounded structures (e.g. a log tree); never on
 *       broad/cyclic graphs.</li>
 *   <li>{@link quiz.annotations.QuizableReference @QuizableReference} — explicit chip;
 *       an intent-marking alias of the default. Kept for clarity and for
 *       fields that must never be force-inlined.</li>
 *   <li>{@link quiz.annotations.Link @Link} — a String URL field; rendered as a
 *       clickable link row (see {@link QuizableLinkRow}).</li>
 *   <li>{@code @NotQuizableField} — not rendered. {@code @MinorField} —
 *       hidden unless the config opts minor fields in.</li>
 * </ul>
 *
 * <h3>Reference UI behaviour</h3>
 * A reference chip shows a ▶/▼ triangle. Left-click toggles
 * <i>expand/collapse in place</i>: expanding flips per-target state in
 * {@link QuizableRenderContext} and rebuilds the card via {@link
 * #refresh()}, rendering the chip plus an inline panel below it. The
 * inline panel's own references are themselves collapsed chips, so each
 * click opens exactly one level — bounded and safe even for large graphs.
 * Shift- or double-click opens the target in its own detail window.
 *
 * <h3>Copy</h3>
 * Painted text rows/blocks support drag-select (or click to select all),
 * {@code Cmd/Ctrl+C}, and a right-click copy menu; chips and link rows
 * offer right-click copy.
 *
 * <h3>Components</h3>
 * Text is drawn by lightweight painted components ({@link QuizableTextRow},
 * {@link QuizableTextBlock}, {@link QuizableReferenceRow}, {@link
 * QuizableLinkRow}) rather than per-value Swing widgets, so a card with
 * tens of thousands of fields stays cheap. The only structural extra is a
 * single top-pinning {@link Box.Filler} per root card.
 */
public class QuizablePanel extends JPanel {
    // A complex collection/map field renders under a collapsible header,
    // collapsed by default (threshold 0 => no list auto-expands); click the
    // header to expand. Toggleable per collection.
    private static final int COLLECTION_COLLAPSE_THRESHOLD = 0;

    private final Quizable quizable;
    private final QuizablePanelConfig config;
    private final boolean fill;

    private Color highlightColor = null;

    // Minimum on-screen footprint for this card, enforced as a floor rather
    // than a frozen preferred size: the card still grows naturally when a
    // reference chip is expanded in place (otherwise GridBag would compress
    // the extra content into the old height, collapsing the image and
    // hiding rows — and the scroll pane couldn't reach the grown top).
    private Dimension cardSizeFloor = null;

    public void setCardSizeFloor(Dimension floor) {
        this.cardSizeFloor = floor;
        if (floor != null) {
            setMinimumSize(new Dimension(
                    Math.min(floor.width, 220), Math.min(floor.height, 220)));
        }
        revalidate();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (cardSizeFloor != null) {
            return new Dimension(
                    Math.max(d.width, cardSizeFloor.width),
                    Math.max(d.height, cardSizeFloor.height));
        }
        return d;
    }

    public void setHighlightColor(Color c) {
        this.highlightColor = c;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (highlightColor != null) {
            g.setColor(highlightColor);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
        super.paintComponent(g);
    }

    private final Set<Object> visited;
    private final Set<Object> ancestors;
    private final QuizableRenderContext renderContext;
    private boolean renderedConfiguredContent = false;

    private final List<String> path;
    private int firstFieldRow = 0;

    // When true, this panel skips its own title header because the name is
    // already shown immediately above it (the reference chip that expanded
    // into it, or a wrapper whose displayName is this object's name). Avoids
    // echoing the same name two/three times down a card. See addRenderedField
    // and collapsibleReference.
    private boolean suppressTitle = false;

    public static <T> Set<T> identitySetOf() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    public QuizablePanel(Quizable quizable,
                         QuizablePanelConfig config,
                         boolean fill) {
        this(identitySetOf(), identitySetOf(), new QuizableRenderContext(),
                true, quizable, config, fill, new ArrayList<>(), null, null);
    }

    // Root render whose own title is suppressed -- e.g. an "Open in window"
    // frame already shows the name in its title bar.
    public QuizablePanel(Quizable quizable,
                         QuizablePanelConfig config,
                         boolean fill,
                         boolean suppressTitle) {
        this(identitySetOf(), identitySetOf(), new QuizableRenderContext(),
                true, quizable, config, fill, new ArrayList<>(), null, null, suppressTitle);
    }

    public QuizablePanel(Quizable quizable,
                         QuizablePanelConfig config,
                         Collection<? extends Quizable> topLevel,
                         boolean fill) {
        this(identitySetOf(), identitySetOf(), new QuizableRenderContext(topLevel),
                true, quizable, config, fill, new ArrayList<>(), null, null);
    }

    public QuizablePanel(Quizable quizable,
                         QuizablePanelConfig config,
                         QuizableRenderContext renderContext,
                         boolean fill) {
        this(identitySetOf(), identitySetOf(), renderContext,
                true, quizable, config, fill, new ArrayList<>(),
             null, null);
    }

    public QuizablePanel(Set<Object> visited,
                         Set<Object> ancestors,
                         QuizableRenderContext renderContext,
                         boolean rootRender,
                         Quizable quizable,
                         QuizablePanelConfig config,
                         boolean fill,
                         List<String> path) {
        this(visited, ancestors, renderContext, rootRender,
                quizable, config, fill, path, null, null);
    }

    public QuizablePanel(Quizable quizable,
                         QuizablePanelConfig config,
                         boolean fill,
                         JComponent compiledView) {
        this(identitySetOf(), identitySetOf(), new QuizableRenderContext(),
                true, quizable, config, fill, new ArrayList<>(),
             null, compiledView);
    }


    /**
     * Shouldn't be static! If static then
     * Arguments can't fit into locals in class file quiz/ui/QuizablePanel$RenderStats
     */
    public final class RenderStats {
        public static final Map<String, Integer> panels = new TreeMap<>();
        public static int textRows = 0;
        public static int textBlocks = 0;
        public static int referenceRows = 0;

        public static void panel(Object q) {
            if (q != null) {
                panels.merge(q.getClass().getSimpleName(), 1, Integer::sum);
            }
        }

        public static void print() {
            System.out.println("TextRows=" + textRows);
            System.out.println("TextBlocks=" + textBlocks);
            System.out.println("ReferenceRows=" + referenceRows);
            System.out.println("Panels=" + panels);
        }
    }

    public QuizablePanel(Set<Object> visited,
                         Set<Object> ancestors,
                         QuizableRenderContext renderContext,
                         boolean rootRender,
                         Quizable quizable,
                         QuizablePanelConfig config,
                         boolean fill,
                         List<String> path,
                         List<Quizable> objectPath,
                         JComponent compiledView) {
        this(visited, ancestors, renderContext, rootRender, quizable, config,
                fill, path, objectPath, compiledView, false);
    }

    public QuizablePanel(Set<Object> visited,
                         Set<Object> ancestors,
                         QuizableRenderContext renderContext,
                         boolean rootRender,
                         Quizable quizable,
                         QuizablePanelConfig config,
                         boolean fill,
                         List<String> path,
                         List<Quizable> objectPath,
                         JComponent compiledView,
                         boolean suppressTitle) {
        this.suppressTitle = suppressTitle;
        RenderStats.panel(quizable);
        // addMouseListener(new DeepComponentInspector());

        List<Quizable> objectPath1 = objectPath == null
                ? new ArrayList<>()
                : new ArrayList<>(objectPath);

        if (rootRender && quizable != null && objectPath1.isEmpty()) {
            objectPath1.add(quizable);
        }

        this.quizable = quizable;
        this.visited = visited == null ? identitySetOf() : visited;
        this.ancestors = ancestors == null ? identitySetOf() : ancestors;
        this.renderContext = renderContext == null
                ? new QuizableRenderContext()
                : renderContext;
        this.fill = fill;
        this.path = path == null ? new ArrayList<>() : new ArrayList<>(path);

        this.config = config == null
                ? QuizablePanelConfig.of(quizable == null ? null : quizable.getClass())
                : config;

        setLayout(new GridBagLayout());
        setOpaque(false);

        if (quizable == null) {
            return;
        }

        if (compiledView != null) {
            this.visited.add(quizable);
            setLayout(new BorderLayout());
            add(compiledView, BorderLayout.CENTER);
            renderedConfiguredContent = true;
            return;
        }

        if (rootRender) {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1, true),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)
            ));
        }

        if (!rootRender) {
            assert ancestors != null;
            if (ancestors.contains(quizable)) {
                addCompactReference(quizable, false);
                return;
            }
        }

        if (!rootRender) {
            assert visited != null;
            if (visited.contains(quizable)) {
                addCompactReference(quizable, false);
                return;
            }
        }

        if (!rootRender && this.renderContext.isTopLevel(quizable)) {
            addCompactReference(quizable, true);
            return;
        }

        this.visited.add(quizable);
        this.ancestors.add(quizable);

        addTitleHeaderIfNeeded();
        buildFields();
        ensureTitleHasRoom();
        this.ancestors.remove(quizable);
    }

    public boolean hasRenderedConfiguredContent() {
        return renderedConfiguredContent;
    }

    /**
     * Rebuilds this card's content in place from the (possibly mutated)
     * backing quizable, keeping the same panel instance so any attached
     * search/sort/scroll/highlight state stays bound to the same card.
     *
     * Targets the standard field-rendered card. Compiled-view cards (which
     * use a BorderLayout wrapper) are left untouched, since their content
     * is an externally supplied component rather than reflected fields.
     * Call on the Event Dispatch Thread.
     */
    public void refresh() {
        if (quizable == null || !(getLayout() instanceof GridBagLayout)) {
            return;
        }

        removeAll();

        firstFieldRow = 0;
        renderedConfiguredContent = false;

        // visited/ancestors are this card's own cycle-detection sets;
        // reset them so the rebuild re-renders nested references that the
        // first pass had already marked as seen.
        visited.clear();
        ancestors.clear();

        visited.add(quizable);
        ancestors.add(quizable);

        addTitleHeaderIfNeeded();
        buildFields();
        ensureTitleHasRoom();

        ancestors.remove(quizable);

        revalidate();
        repaint();
    }

    private void addTitleHeaderIfNeeded() {
        String title = getTitle();

        if (title == null || title.isEmpty() || suppressTitle || wrapsSameNameChild()) {
            firstFieldRow = 0;
            return;
        }

        renderedConfiguredContent = true;

        add(createTitleHeader(quizable),
                GridBagUtils.gbc(
                        0, 0,
                        1.0, 0.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(2, 2, 4, 2)));

        firstFieldRow = 1;
    }

    private JComponent createTitleHeader(Quizable q) {
        return createTitleHeader(q, false);
    }

    private JComponent createTitleHeader(Quizable q, boolean focusTopLevel) {
        String title = safeName(q);

        if (title.isEmpty()) {
            title = String.valueOf(q);
        }

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        titleLabel.setForeground(new Color(0, 80, 180));
        titleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleLabel.setToolTipText(
                focusTopLevel
                        ? "Click to focus existing panel"
                        : "Double-click to open full view");

        titleLabel.putClientProperty(QuizableSearchPanel.FIELD_NAME_PROPERTY, "name");
        titleLabel.putClientProperty(QuizableSearchPanel.FIELD_VALUE_PROPERTY, title);
        titleLabel.putClientProperty(QuizableSearchPanel.FIELD_PATH_PROPERTY, List.of("name"));

        if (config.isAddListener()) {
            if (focusTopLevel) {
                addFocusTopLevelListener(titleLabel, q);
            } else {
                addOpenListener(titleLabel, q);
            }
        }

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, Color.LIGHT_GRAY));
        header.add(titleLabel, BorderLayout.WEST);

        return header;
    }

    private void addCompactReference(Quizable q, boolean focusTopLevel) {
        QuizablePanelConfig openCfg = configForNested(q);

        addSingle(
                new QuizableReferenceRow(
                        "",
                        namePath(path),
                        q,
                        renderContext,
                        openCfg,
                        objectPathTitle(q),
                        false),
                0);

        setMinimumSize(new Dimension(100, 42));
    }

    private String objectPathTitle(Quizable target) {
        List<String> names = new ArrayList<>();

        if (quizable != null
                && quizable.getName() != null
                && !quizable.getName().isBlank()) {
            names.add(quizable.getName());
        }

        if (target != null
                && target.getName() != null
                && !target.getName().isBlank()) {
            names.add(target.getName());
        }

        return String.join(" → ", names);
    }

    private void buildFields() {
        int row = firstFieldRow;

        List<QuizableTextBlock.Row> textRows = new ArrayList<>();

        for (Field field : config.visibleFieldsFor(quizable.getClass())) {
            String name = field.getName();

            if ("name".equals(name)) {
                continue;
            }

            Object value;

            try {
                value = field.get(quizable);
            } catch (Exception e) {
                continue;
            }

            // A field that renders nothing must not break the text-block
            // batch: a null/empty leaf (e.g. a blank error) sitting between
            // two value leaves would otherwise split them into separate
            // blocks and open a stray, variable vertical gap.
            if (value == null || isEmptyCollectionOrMap(value)) {
                continue;
            }

            List<String> fieldPath = new ArrayList<>(path);
            fieldPath.add(name);

            if (isTextBlockCandidate(field, value)) {
                textRows.add(textBlockRow(name, fieldPath, value));
                continue;
            }

            if (!textRows.isEmpty()) {
                row = addTextBlock(textRows, row);
                textRows.clear();
            }

            row = addRenderedField(field, value, row);
        }

        if (!textRows.isEmpty()) {
            row = addTextBlock(textRows, row);
        }

        // Root cards only: pin fields to the top by absorbing any extra
        // card height in one zero-paint filler, instead of letting GridBag
        // centre the content (which left a variable gap). Nested panels are
        // content-sized, so they don't need it.
        if (path.isEmpty()) {
            add(Box.createGlue(), GridBagUtils.gbc(
                    0, row + 1, 1.0, 1.0,
                    GridBagConstraints.NORTHWEST,
                    GridBagConstraints.BOTH,
                    new Insets(0, 0, 0, 0)));
        }
    }

    private int addTextBlock(List<QuizableTextBlock.Row> rows, int row) {
        QuizableTextBlock block = new QuizableTextBlock(rows);

        if (!block.isEmpty()) {
            addSingle(block, row++);
        }

        return row;
    }

    private int addRenderedField(Field field, Object value, int row) {
        if (value == null || isEmptyCollectionOrMap(value)) {
            return row;
        }

        String fieldName = field.getName();
        List<String> fieldPath = new ArrayList<>(path);
        fieldPath.add(fieldName);

        // @Provenance (a Source) renders like @QuizableReference: a collapsed
        // chip, never force-inlined — the annotation drives the chipping.
        //
        // The dynamic-field container map of a DynamicFields object (e.g. a raw
        // WikidataDynamicObject) must NOT be treated as one collapsible group —
        // its entries ARE the object's fields, so collapsing it hides all the
        // content behind a "dynamicFields (n)" header. Render it normally; only
        // genuine value collections/maps collapse.
        boolean isDynamicContainer =
                quizable instanceof quiz.DynamicFields df
                        && value == df.dynamicFieldValues();
        boolean isCollectionOrMap =
                (value instanceof Collection<?> || value instanceof Map<?, ?>)
                        && !isDynamicContainer;

        if (QuizableAdapter.isQuizableReference(field)
                || QuizableAdapter.isProvenanceField(field)) {
            if (isCollectionOrMap) {
                // The header labels the field; build the items borderless.
                Object v = value;
                return addCollapsibleCollection(fieldName, fieldPath, value, row,
                        () -> createReferenceFieldComponent("", fieldPath, v));
            }
            JComponent comp =
                    createReferenceFieldComponent(fieldName, fieldPath, value);

            if (comp != null) {
                addSingle(comp, row++);
            }

            return row;
        }

        // @QuizableInline means "always render fully expanded inline" (e.g. a
        // query-log step tree) — never collapse it, or the nested content (the
        // SPARQL, child steps) hides behind a collapsed header.
        if (QuizableAdapter.isQuizableInline(field)) {
            JComponent comp =
                    createInlineFieldComponent(fieldName, fieldPath, value);

            if (comp != null) {
                addSingle(comp, row++);
            }

            return row;
        }

        if (QuizableAdapter.isLinkField(field)
                && value instanceof String url
                && !url.isBlank()) {

            quiz.annotations.Link link = field.getAnnotation(quiz.annotations.Link.class);
            String label = link == null ? "" : link.text();
            addSingle(new QuizableLinkRow(fieldName, fieldPath, url, label), row++);
            return row;
        }

        // A bare (non-annotated) single Quizable is a collapsible chip too,
        // matching collection members -- see the class doc.
        if (value instanceof Quizable q) {
            JComponent comp = collapsibleReference(fieldName, fieldPath, q);

            if (comp != null) {
                addSingle(comp, row++);
            }

            return row;
        }

        // Quiz query panels: show the answer-hiding (masked/blurred) image.
        if (value instanceof ImagePane ip && config.isBlurImages() && quizable != null) {
            value = blurForQuiz(ip);
        }

        QuizablePanelConfig fieldCfg = config.getFieldConfig(fieldName);

        if (fieldCfg == null) {
            fieldCfg = defaultConfigForValue(value);
        }

        // A complex collection/map (simple ones already folded into a text
        // block) renders under a collapsible header; build the items borderless
        // (the header carries the field name) and only when expanded.
        if (isCollectionOrMap) {
            QuizablePanelConfig cfg = fieldCfg;
            Object collValue = value;
            return addCollapsibleCollection(fieldName, fieldPath, value, row,
                    () -> QuizableValueRenderer.createFieldComponent(
                            copyVisited(), copyAncestors(), renderContext,
                            "", fieldPath, collValue, cfg, fill));
        }

        JComponent comp = QuizableValueRenderer.createFieldComponent(
                copyVisited(),
                copyAncestors(),
                renderContext,
                fieldName,
                fieldPath,
                value,
                fieldCfg,
                fill);

        if (comp != null) {
            addSingle(comp, row++);
        }

        return row;
    }

    // Renders a complex collection/map field as a collapsible group: a clickable
    // "{field} (N)" header plus, when expanded, the items built by {@code body}.
    // Lists over COLLECTION_COLLAPSE_THRESHOLD start collapsed; the per-collection
    // toggle is remembered in the render context (keyed by the collection's
    // identity), and the body is built only when expanded so a collapsed long
    // list stays cheap.
    private int addCollapsibleCollection(
            String fieldName,
            List<String> fieldPath,
            Object value,
            int row,
            java.util.function.Supplier<JComponent> body) {

        int count = value instanceof Collection<?> c ? c.size()
                : value instanceof Map<?, ?> m ? m.size()
                : 0;
        if (count == 0) {
            return row;
        }

        boolean defaultExpanded = count <= COLLECTION_COLLAPSE_THRESHOLD;
        boolean expanded =
                renderContext.isCollectionExpanded(value, defaultExpanded);

        QuizableCollectionHeader header = new QuizableCollectionHeader(
                fieldName, fieldPath, count, expanded, value,
                defaultExpanded, renderContext);

        if (!expanded) {
            addSingle(header, row++);
            return row;
        }

        JComponent items = body.get();
        if (items == null) {
            addSingle(header, row++);
            return row;
        }

        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);
        wrap.add(header, GridBagUtils.gbc(
                0, 0, 1.0, 0.0,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.HORIZONTAL,
                new Insets(0, 0, 0, 0)));
        wrap.add(items, GridBagUtils.gbc(
                0, 1, 1.0, 0.0,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.HORIZONTAL,
                new Insets(0, 16, 2, 0)));

        addSingle(wrap, row++);
        return row;
    }

    private JComponent createReferenceFieldComponent(
            String fieldName,
            List<String> fieldPath,
            Object value
                                                    ) {
        if (value instanceof Quizable q) {
            return collapsibleReference(fieldName, fieldPath, q);
        }

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        if (fieldName != null && !fieldName.isBlank()) {
            panel.setBorder(BorderFactory.createTitledBorder(fieldName));
        }

        int row = 0;

        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Quizable q) {
                    addReferenceToPanel(panel, "", q, fieldPath, row++);
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (item instanceof Quizable q) {
                    addReferenceToPanel(panel, "", q, fieldPath, row++);
                }
            }
        }

        return row == 0 ? null : panel;
    }

    // Opposite of createReferenceFieldComponent: each nested Quizable is
    // expanded fully in place rather than shown as a click-to-open chip.
    // Only reached for @QuizableInline fields, so the broad/cyclic graphs
    // that rely on the reference default are never expanded here.
    private JComponent createInlineFieldComponent(
            String fieldName,
            List<String> fieldPath,
            Object value) {

        if (value instanceof Quizable q) {
            return inlineQuizable(q, fieldPath);
        }

        Collection<?> items =
                value instanceof Collection<?> c ? c
                        : value instanceof Map<?, ?> m ? m.values()
                        : List.of();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        if (fieldName != null && !fieldName.isBlank()) {
            panel.setBorder(BorderFactory.createTitledBorder(fieldName));
        }

        int row = 0;

        for (Object item : items) {
            if (!(item instanceof Quizable q)) {
                continue;
            }

            JComponent nested = inlineQuizable(q, fieldPath);

            if (nested != null) {
                panel.add(
                        nested,
                        GridBagUtils.gbc(
                                0, row++,
                                1.0, 0.0,
                                GridBagConstraints.NORTHWEST,
                                GridBagConstraints.HORIZONTAL,
                                new Insets(2, 6, 2, 6)));
            }
        }

        return row == 0 ? null : panel;
    }

    private JComponent inlineQuizable(Quizable q, List<String> fieldPath) {
        return inlineQuizable(q, fieldPath, false);
    }

    // suppressTitle: the name is already shown above (the chip that expanded
    // into this body, or a same-named wrapper), so don't repeat it as a title.
    private JComponent inlineQuizable(Quizable q, List<String> fieldPath, boolean suppressTitle) {
        QuizablePanel nested =
                new QuizablePanel(
                        copyVisited(),
                        copyAncestors(),
                        renderContext,
                        false,
                        q,
                        configForNested(q),
                        fill,
                        fieldPath,
                        null,
                        null,
                        suppressTitle);

        return nested.hasRenderedConfiguredContent() ? nested : null;
    }

    private void addReferenceToPanel(
            JPanel panel,
            String fieldName,
            Quizable q,
            List<String> fieldPath,
            int row
    ) {
        panel.add(collapsibleReference(fieldName, fieldPath, q),
                GridBagUtils.gbc(
                        0, row,
                        1.0, 0.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(2, 6, 2, 6)));
    }

    // A Quizable reference renders as a collapsed chip by default; clicking
    // it (see QuizableReferenceRow) flips renderContext expand state and
    // rebuilds the card, so here it renders the chip plus the inline panel.
    // Children of the inline panel are themselves collapsed chips, so only
    // one level opens per click -- safe even for broad/cyclic graphs.
    private JComponent collapsibleReference(
            String fieldName,
            List<String> fieldPath,
            Quizable target) {

        // A reference to something that is itself a top-level card in this view
        // is a navigation link (jump to that card) rather than an expand-in-place
        // chip — so the same object never has two competing expand toggles.
        if (renderContext != null && renderContext.isTopLevel(target)) {
            return new QuizableReferenceRow(
                    fieldName,
                    namePath(fieldPath),
                    target,
                    renderContext,
                    configForNested(target),
                    objectPathTitle(target),
                    false,
                    true);
        }

        boolean exp = renderContext != null && renderContext.isExpanded(target);

        QuizableReferenceRow chip =
                new QuizableReferenceRow(
                        fieldName,
                        namePath(fieldPath),
                        target,
                        renderContext,
                        configForNested(target),
                        objectPathTitle(target),
                        exp);

        if (!exp) {
            return chip;
        }

        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);

        wrap.add(chip, GridBagUtils.gbc(
                0, 0, 1.0, 0.0,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.HORIZONTAL,
                new Insets(0, 0, 0, 0)));

        // The chip directly above already shows the target's name, so the
        // expanded body must not repeat it as its own title header.
        JComponent inline = inlineQuizable(target, fieldPath, true);

        if (inline != null) {
            wrap.add(inline, GridBagUtils.gbc(
                    0, 1, 1.0, 0.0,
                    GridBagConstraints.NORTHWEST,
                    GridBagConstraints.HORIZONTAL,
                    new Insets(0, 16, 4, 0)));
        }

        return wrap;
    }

    private QuizableTextBlock.Row textBlockRow(
            String fieldName,
            List<String> fieldPath,
            Object value) {

        List<String> lines = new ArrayList<>();

        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    lines.add("• " + item);
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                lines.add(String.valueOf(e.getKey()) + " -> " + String.valueOf(e.getValue()));
            }
        } else {
            lines.add(String.valueOf(value));
        }

        return new QuizableTextBlock.Row(
                fieldName,
                new ArrayList<>(fieldPath),
                value,
                lines);
    }

    private boolean isTextBlockCandidate(Field field, Object value) {
        if (value == null || isEmptyCollectionOrMap(value)) {
            return false;
        }

        if (QuizableAdapter.isQuizableReference(field)
                || QuizableAdapter.isProvenanceField(field)) {
            return false;
        }

        // @Link string fields render as a dedicated clickable row rather
        // than folding into the (drag-to-select) text block.
        if (QuizableAdapter.isLinkField(field)
                && value instanceof String s
                && !s.isBlank()) {
            return false;
        }

        if (value instanceof Quizable) {
            return false;
        }

        if (value instanceof ImagePane) {
            return false;
        }

        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                if (v instanceof Quizable || v instanceof ImagePane
                        || v instanceof Collection<?> || v instanceof Map<?, ?>) {
                    return false;
                }
            }
            return true;
        }

        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Quizable || item instanceof ImagePane
                        || item instanceof Collection<?> || item instanceof Map<?, ?>) {
                    return false;
                }
            }
            return true;
        }

        return true;
    }

    private static List<String> namePath(List<String> base) {
        List<String> out =
                new ArrayList<>(base == null ? List.of() : base);

        if (out.isEmpty()
                || !"name".equals(out.getLast())) {
            out.add("name");
        }

        return out;
    }

    private boolean isEmptyCollectionOrMap(Object value) {
        if (value instanceof Collection<?> c) {
            return c.isEmpty();
        }

        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }

        return false;
    }

    private QuizablePanelConfig defaultConfigForValue(Object value) {
        if (value instanceof Quizable q) {
            return configForNested(q);
        }

        if (value instanceof Collection<?> col) {
            for (Object item : col) {
                if (item instanceof Quizable q) {
                    return configForNested(q);
                }
            }
        }

        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                if (v instanceof Quizable q) {
                    return configForNested(q);
                }
            }
        }

        return QuizablePanelConfig.leaf()
                .setAddListener(config.isAddListener())
                .setThumb(config.isThumb());
    }

    private QuizablePanelConfig configForNested(Quizable q) {
        QuizablePanelConfig fromContext =
                renderContext.configFor(q.getClass());

        if (fromContext != null) {
            return fromContext
                    .setAddListener(config.isAddListener())
                    .setThumb(config.isThumb());
        }

        return QuizablePanelConfig.all(q.getClass())
                .setAddListener(config.isAddListener())
                .setThumb(config.isThumb());
    }

    private Set<Object> copyVisited() {
        Set<Object> copy = identitySetOf();
        copy.addAll(visited);
        return copy;
    }

    private Set<Object> copyAncestors() {
        Set<Object> copy = identitySetOf();
        copy.addAll(ancestors);
        return copy;
    }

    private void addSingle(Component comp, int row) {
        renderedConfiguredContent = true;

        add(comp, GridBagUtils.gbc(
                0, row,
                1.0, 0.0,
                GridBagConstraints.NORTHWEST,
                GridBagConstraints.HORIZONTAL,
                new Insets(2, 2, 2, 2)));
    }

    private void ensureTitleHasRoom() {
        String title = getTitle();

        if (title == null || title.isEmpty()) {
            return;
        }

        if (getComponentCount() == 0) {
            Font font = UIManager.getFont("TitledBorder.font");

            if (font == null) {
                font = getFont();
            }

            FontMetrics fm = getFontMetrics(font);

            Dimension d = new Dimension(
                    Math.max(140, fm.stringWidth(title) + 30),
                    Math.max(40, fm.getHeight() + 18));

            setPreferredSize(d);
            setMinimumSize(d);
        }
    }

    private void addFocusTopLevelListener(Component c, Quizable q) {
        c.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.isConsumed()) {
                    return;
                }

                e.consume();

                if (!renderContext.focusTopLevel(q)) {
                    openInFrame(q);
                }
            }
        });
    }

    private static String shortValue(Object v) {
        switch (v) {
            case null -> {
                return "null";
            }
            case Collection<?> c -> {
                return "Collection size=" + c.size();
            }
            case Map<?, ?> m -> {
                return "Map size=" + m.size();
            }
            default -> {
            }
        }

        String s = String.valueOf(v);
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }


    private void addOpenListener(Component c, Quizable q) {
        //System.out.println("ADD open listener to " + q.getName());
        c.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.isConsumed()) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    e.consume();
                    openInFrame(q);
                }
            }
        });
    }

    private void openInFrame(Quizable q) {
        new QuizableFrame(q,
                QuizablePanelConfig.allWithMinorFields(q.getClass())
                        .setAddListener(config.isAddListener())
                        .setThumb(config.isThumb()));
    }

    private String safeName(Quizable q) {
        String n = q == null ? null : q.getName();
        return n == null ? "" : n;
    }

    // Replace a query image with its answer-hiding version (hand mask, else
    // runtime OCR). Best-effort: returns the original ImagePane on any failure.
    private Object blurForQuiz(ImagePane original) {
        String type = quizable.typeName();
        String name = quizable.getDisplayName();
        try {
            if (!quiz.ocr.QuizImageBlurrer.blurs(type, name)) {
                return original;
            }
            java.awt.image.BufferedImage src =
                    toBufferedImage(original.getCachedImage().getFullImage());
            java.awt.image.BufferedImage blurred =
                    quiz.ocr.QuizImageBlurrer.blur(type, name, src);
            if (blurred == src) {
                return original;
            }
            return new ImagePane(name, quizable, new aux.CachedImage(blurred), false, false);
        } catch (Throwable e) {
            return original;
        }
    }

    private static java.awt.image.BufferedImage toBufferedImage(java.awt.Image img) {
        if (img instanceof java.awt.image.BufferedImage b) {
            return b;
        }
        java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(
                Math.max(1, img.getWidth(null)), Math.max(1, img.getHeight(null)),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = b.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return b;
    }

    // A thin wrapper whose own name IS a single child's name (President ->
    // Person, both "George Washington"; the name was historically the shared
    // identifier). Drop this card's bold title so the name shows once -- on the
    // child's chip, which keeps its Open-in-window / expand behaviour.
    private boolean wrapsSameNameChild() {
        if (quizable == null) {
            return false;
        }
        String owner = safeName(quizable);
        if (owner.isEmpty()) {
            return false;
        }
        boolean sameNamedChild = false;
        int otherValuedFields = 0;
        for (Field field : config.visibleFieldsFor(quizable.getClass())) {
            if ("name".equals(field.getName())) {
                continue;
            }
            Object value;
            try {
                value = field.get(quizable);
            } catch (Exception e) {
                continue;
            }
            if (value == null) {
                continue;
            }
            if (value instanceof Quizable child && owner.equals(safeName(child))) {
                sameNamedChild = true;
            } else {
                otherValuedFields++;
            }
        }
        // Only a thin wrapper whose *sole* content is the same-named child
        // suppresses its own title (to avoid echoing the name). A full card
        // that merely has a coincidentally same-named reference field — e.g.
        // the constellation Andromeda whose "named after" is the figure
        // Andromeda — must still show its title.
        return sameNamedChild && otherValuedFields == 0;
    }

    public Quizable getQuizable() {
        return quizable;
    }

    /**
     * Expands any collapsed collection/map lying on {@code path} (relative to
     * this card's quizable), so a search match hidden inside a collapsed list
     * becomes rendered (and thus highlightable / scrollable). Only flips
     * currently-collapsed collections; returns true if anything changed, so the
     * caller can {@link #refresh()} once. Does not itself refresh.
     */
    public boolean expandCollectionsOnPath(List<String> searchPath) {
        if (searchPath == null || searchPath.isEmpty() || quizable == null) {
            return false;
        }
        return expandCollectionsAlong(quizable, searchPath, 0);
    }

    private boolean expandCollectionsAlong(Object obj, List<String> path, int idx) {
        if (obj == null || idx > path.size()) {
            return false;
        }

        if (obj instanceof Collection<?> c) {
            boolean changed = expandIfCollapsed(obj, c.size());
            for (Object item : c) {
                changed |= expandCollectionsAlong(item, path, idx);
            }
            return changed;
        }

        if (obj instanceof Map<?, ?> m) {
            boolean changed = expandIfCollapsed(obj, m.size());
            for (Object v : m.values()) {
                changed |= expandCollectionsAlong(v, path, idx);
            }
            return changed;
        }

        if (idx >= path.size()) {
            return false;
        }

        String part = path.get(idx);
        // "name" is a synthetic leaf (Quizable.getName()), not a real field.
        if ("name".equals(part)) {
            return false;
        }

        Field f = QuizableAdapter.getField(obj.getClass(), part);
        if (f == null) {
            return false;
        }
        try {
            f.setAccessible(true);
            return expandCollectionsAlong(f.get(obj), path, idx + 1);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean expandIfCollapsed(Object collectionKey, int count) {
        boolean defaultExpanded = count <= COLLECTION_COLLAPSE_THRESHOLD;
        if (!renderContext.isCollectionExpanded(collectionKey, defaultExpanded)) {
            renderContext.setCollectionExpanded(collectionKey, true);
            return true;
        }
        return false;
    }

    public String getTitle() {
        return (config.isAllFields() || config.getFields().containsKey("name"))
                ? safeName(quizable)
                : "";
    }
}