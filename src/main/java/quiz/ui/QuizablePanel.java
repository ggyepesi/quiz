package quiz.ui;

import aux.DeepComponentInspector;
import aux.GridBagUtils;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;

public class QuizablePanel extends JPanel {
    private final Quizable quizable;
    private final QuizablePanelConfig config;
    private final boolean fill;

    private final Set<Object> visited;
    private final Set<Object> ancestors;
    private final QuizableRenderContext renderContext;
    private boolean renderedConfiguredContent = false;

    private final List<String> path;
    private final List<Quizable> objectPath;
    private int firstFieldRow = 0;

    public static <T> Set<T> identitySetOf() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    public static <T> Set<T> identitySetOf(Collection<? extends T> values) {
        Set<T> set = identitySetOf();

        if (values != null) {
            set.addAll(values);
        }

        return set;
    }

    public QuizablePanel(Quizable quizable,
                         QuizablePanelConfig config,
                         boolean fill) {
        this(identitySetOf(), identitySetOf(), new QuizableRenderContext(),
                true, quizable, config, fill, new ArrayList<>(), null, null);
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
        RenderStats.panel(quizable);
        addMouseListener(new DeepComponentInspector());

        this.objectPath = objectPath == null
                ? new ArrayList<>()
                : new ArrayList<>(objectPath);

        if (rootRender && quizable != null && this.objectPath.isEmpty()) {
            this.objectPath.add(quizable);
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

        if (!rootRender && ancestors.contains(quizable)) {
            addCompactReference(quizable, false);
            return;
        }

        if (!rootRender && visited.contains(quizable)) {
            addCompactReference(quizable, false);
            return;
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

    private void addTitleHeaderIfNeeded() {
        String title = getTitle();

        if (title == null || title.isEmpty()) {
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
                        objectPathTitle(q)),
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

            row = addRenderedField(field, row);
        }

        if (!textRows.isEmpty()) {
            addTextBlock(textRows, row);
        }
    }

    private int addTextBlock(List<QuizableTextBlock.Row> rows, int row) {
        QuizableTextBlock block = new QuizableTextBlock(rows);

        if (!block.isEmpty()) {
            addSingle(block, row++);
        }

        return row;
    }

    private int addRenderedField(Field field, int row) {
        Object value;

        try {
            value = field.get(quizable);
        } catch (Exception e) {
            return row;
        }

        if (value == null || isEmptyCollectionOrMap(value)) {
            return row;
        }

        String fieldName = field.getName();

        List<String> fieldPath = new ArrayList<>(path);
        fieldPath.add(fieldName);

        if (QuizableAdapter.isQuizableReference(field)) {
            JComponent comp =
                    createReferenceFieldComponent(fieldName, fieldPath, value);

            if (comp != null) {
                addSingle(comp, row++);
            }

            return row;
        }

        QuizablePanelConfig fieldCfg = config.getFieldConfig(fieldName);

        if (fieldCfg == null) {
            fieldCfg = defaultConfigForValue(value);
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

    private JComponent createReferenceFieldComponent(
            String fieldName,
            List<String> fieldPath,
            Object value
                                                    ) {
        if (value instanceof Quizable q) {
            return new QuizableReferenceRow(
                    fieldName,
                    namePath(fieldPath),
                    q,
                    renderContext,
                    configForNested(q),
                    objectPathTitle(q));
        }

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createTitledBorder(fieldName));

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

    private void addReferenceToPanel(
            JPanel panel,
            String fieldName,
            Quizable q,
            List<String> fieldPath,
            int row
    ) {
        QuizablePanelConfig openCfg = configForNested(q);

        QuizableReferenceRow ref =
                new QuizableReferenceRow(
                        fieldName,
                        namePath(fieldPath),
                        q,
                        renderContext,
                        openCfg,
                        objectPathTitle(q));

        panel.add(ref,
                GridBagUtils.gbc(
                        0, row,
                        1.0, 0.0,
                        GridBagConstraints.NORTHWEST,
                        GridBagConstraints.HORIZONTAL,
                        new Insets(2, 6, 2, 6)));
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

        if (QuizableAdapter.isQuizableReference(field)) {
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
                || !"name".equals(out.get(out.size() - 1))) {
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

    private void addOpenListener(Component c, Quizable q) {
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

    public Quizable getQuizable() {
        return quizable;
    }

    public String getTitle() {
        return (config.isAllFields() || config.getFields().containsKey("name"))
                ? safeName(quizable)
                : "";
    }
}