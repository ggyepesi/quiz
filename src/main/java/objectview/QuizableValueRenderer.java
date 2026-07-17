package objectview;

import aux.GridBagUtils;
import objectview.Viewable;
import objectview.viewconfig.QuizablePanelConfig;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public final class QuizableValueRenderer {
    private QuizableValueRenderer() {
    }

    public static JComponent createFieldComponent(
            Set<Object> visited, Set<Object> ancestors, QuizableRenderContext renderContext,
            String fieldName, List<String> fieldPath, Object value,
            QuizablePanelConfig config, boolean fill) {
        if (value == null) {
            return null;
        }

        // A backing media value (e.g. a Wikidata image) becomes a real ImagePane
        // here, at render time — so the data pool never has to carry Swing.
        if (value instanceof MediaValue media) {
            value = imagePaneFor(media);
            if (value == null) {
                return null;
            }
        }

        if (value instanceof ImagePane imagePane) {
            return imageComponent(fieldName, fieldPath, imagePane);
        }

        if (value instanceof Viewable q) {
            return quizableComponent(visited, ancestors, renderContext, fieldName, fieldPath, q, config, fill);
        }

        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }

            if (isSimpleCollection(collection)) {
                return simpleCollectionComponent(fieldName, fieldPath, collection);
            }

            return complexCollectionComponent(visited, ancestors, renderContext, fieldName, fieldPath, collection, config, fill);
        }

        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return null;
            }

            if (isSimpleMap(map)) {
                return simpleMapComponent(fieldName, fieldPath, map);
            }

            return mapComponent(visited, ancestors, renderContext, fieldName, fieldPath, map, config, fill);
        }

        return leafComponent(fieldName, fieldPath, value);
    }

    /** Builds a (lazy-loading) ImagePane from a backing media value, or null if
     *  it has no URL / can't be built. */
    private static ImagePane imagePaneFor(MediaValue media) {
        String url = media.mediaUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return new ImagePane(media.mediaLabel(), url, null, false, media.mediaSvg(), false);
        } catch (Exception e) {
            return null;
        }
    }

    private static JComponent imageComponent(String fieldName, List<String> fieldPath, ImagePane imagePane) {
        JPanel panel = basePanel(fieldName, fieldPath, imagePane);

        panel.add(imagePane, GridBagUtils.gbc(0, 0, 1.0, 1.0, GridBagConstraints.CENTER, GridBagConstraints.BOTH, new Insets(2, 2, 2, 2)));

        return panel;
    }

    private static JComponent quizableComponent(
            Set<Object> visited, Set<Object> ancestors, QuizableRenderContext renderContext,
            String fieldName, List<String> fieldPath, Viewable q, QuizablePanelConfig config, boolean fill) {
        return quizableComponent(visited, ancestors, renderContext, fieldName, fieldPath, q, config, fill, false);
    }

    private static JComponent quizableComponent(
            Set<Object> visited, Set<Object> ancestors, QuizableRenderContext renderContext,
            String fieldName, List<String> fieldPath, Viewable q, QuizablePanelConfig config, boolean fill,
            boolean suppressTitle) {
        JPanel panel = basePanel(fieldName, fieldPath, q);

        QuizablePanel nested = new QuizablePanel(
                visited, ancestors, renderContext, false, q, config, fill, fieldPath, null, null, suppressTitle);

        if (!nested.hasRenderedConfiguredContent()) {
            return null;
        }

        panel.add(nested, GridBagUtils.gbc(0, 0, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL, new Insets(2, 2, 2, 2)));

        return panel;
    }

    private static JComponent simpleCollectionComponent(String fieldName, List<String> fieldPath, Collection<?> collection) {
        List<String> lines = collection.stream().filter(Objects::nonNull).map(String::valueOf).filter(s -> !s.isBlank()).map(s -> "• " + s).collect(Collectors.toList());

        if (lines.isEmpty()) {
            return null;
        }

        return new QuizableTextRow(fieldName, new ArrayList<>(fieldPath), lines);
    }

    private static JComponent complexCollectionComponent(Set<Object> visited, Set<Object> ancestors, QuizableRenderContext renderContext, String fieldName, List<String> fieldPath, Collection<?> collection, QuizablePanelConfig config, boolean fill) {
        JPanel panel = basePanel(fieldName, fieldPath, collection);

        int row = 0;

        for (Object item : collection) {
            JComponent child = createCollectionItemComponent(visited, ancestors, renderContext, fieldPath, item, config, fill);

            if (child == null) {
                continue;
            }

            panel.add(child, GridBagUtils.gbc(0, row++, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL, new Insets(2, 2, 2, 2)));
        }

        return row == 0 ? null : panel;
    }

    private static JComponent mapComponent(Set<Object> visited, Set<Object> ancestors, QuizableRenderContext renderContext, String fieldName, List<String> fieldPath, Map<?, ?> map, QuizablePanelConfig config, boolean fill) {
        JPanel panel = basePanel(fieldName, fieldPath, map);

        int row = 0;

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            JPanel entryPanel = new JPanel(new BorderLayout(6, 0));
            entryPanel.setOpaque(false);

            JLabel keyLabel = new JLabel(String.valueOf(entry.getKey()));
            keyLabel.setFont(keyLabel.getFont().deriveFont(Font.BOLD));

            JComponent valueComponent = createCollectionItemComponent(visited, ancestors, renderContext, fieldPath, entry.getValue(), config, fill);

            if (valueComponent == null) {
                continue;
            }

            entryPanel.add(keyLabel, BorderLayout.WEST);
            entryPanel.add(valueComponent, BorderLayout.CENTER);

            panel.add(entryPanel, GridBagUtils.gbc(0, row++, 1.0, 0.0, GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL, new Insets(2, 2, 2, 2)));
        }

        return row == 0 ? null : panel;
    }

    private static JComponent simpleMapComponent(String fieldName, List<String> fieldPath, Map<?, ?> map) {
        String text = map.entrySet().stream().filter(e -> e.getKey() != null || e.getValue() != null).map(e -> e.getKey() + " -> " + e.getValue()).filter(s -> !s.isBlank()).collect(Collectors.joining(", "));

        if (text.isBlank()) {
            return null;
        }

        return new QuizableTextRow(fieldName, new ArrayList<>(fieldPath), text);
    }

    private static JComponent createCollectionItemComponent(Set<Object> visited, Set<Object> ancestors, QuizableRenderContext renderContext, List<String> fieldPath, Object item, QuizablePanelConfig config, boolean fill) {
        if (item == null) {
            return null;
        }

        if (item instanceof MediaValue media) {
            ImagePane pane = imagePaneFor(media);
            return pane == null ? null : imageComponent("", fieldPath, pane);
        }

        if (item instanceof ImagePane imagePane) {
            return imageComponent("", fieldPath, imagePane);
        }


        if (item instanceof Viewable q) {
            // A member that is itself a top-level card navigates to it instead
            // of expanding in place (see QuizablePanel.collapsibleReference).
            if (renderContext != null && renderContext.isTopLevel(q)) {
                return new QuizableReferenceRow(
                        "", fieldPath, q, renderContext, config, q.getName(), false, true);
            }

            boolean exp = renderContext != null && renderContext.isExpanded(q);

            QuizableReferenceRow chip =
                    new QuizableReferenceRow(
                            "", fieldPath, q, renderContext, config, q.getName(), exp);

            if (!exp) {
                return chip;
            }

            JPanel wrap = new JPanel(new GridBagLayout());
            wrap.setOpaque(false);

            wrap.add(chip, GridBagUtils.gbc(
                    0, 0, 1.0, 0.0,
                    GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL,
                    new Insets(0, 0, 0, 0)));

            // The chip above already shows the name, so suppress the expanded
            // body's own title header (mirrors QuizablePanel.collapsibleReference).
            JComponent inline = quizableComponent(
                    visited, ancestors, renderContext, "", fieldPath, q, config, fill, true);

            if (inline != null) {
                wrap.add(inline, GridBagUtils.gbc(
                        0, 1, 1.0, 0.0,
                        GridBagConstraints.NORTHWEST, GridBagConstraints.HORIZONTAL,
                        new Insets(0, 16, 4, 0)));
            }

            return wrap;
        }

        if (item instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }

            if (isSimpleCollection(collection)) {
                return simpleCollectionComponent("", fieldPath, collection);
            }

            return complexCollectionComponent(visited, ancestors, renderContext, "", fieldPath, collection, config, fill);
        }

        if (item instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return null;
            }

            if (isSimpleMap(map)) {
                return simpleMapComponent("", fieldPath, map);
            }

            return mapComponent(visited, ancestors, renderContext, "", fieldPath, map, config, fill);
        }

        return leafComponent("", fieldPath, item);
    }

    private static JComponent leafComponent(String fieldName, List<String> fieldPath, Object value) {
        if (value == null) {
            return null;
        }

        String text = String.valueOf(value);

        if (text.isBlank()) {
            return null;
        }

        return new QuizableTextRow(fieldName, new ArrayList<>(fieldPath), value);
    }

    private static boolean isSimpleCollection(Collection<?> collection) {
        for (Object item : collection) {
            if (item == null) {
                continue;
            }

            if (item instanceof Viewable) {
                return false;
            }

            if (item instanceof ImagePane || item instanceof MediaValue) {
                return false;
            }

            if (item instanceof Collection<?>) {
                return false;
            }

            if (item instanceof Map<?, ?>) {
                return false;
            }
        }

        return true;
    }

    private static boolean isSimpleMap(Map<?, ?> map) {
        for (Object value : map.values()) {
            if (value == null) {
                continue;
            }

            if (value instanceof Viewable) {
                return false;
            }

            if (value instanceof ImagePane || value instanceof MediaValue) {
                return false;
            }

            if (value instanceof Collection<?>) {
                return false;
            }

            if (value instanceof Map<?, ?>) {
                return false;
            }
        }

        return true;
    }

    private static JPanel basePanel(String fieldName, List<String> fieldPath, Object value) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        if (fieldName != null && !fieldName.isBlank()) {
            panel.setBorder(BorderFactory.createTitledBorder(fieldName));
        }

        panel.putClientProperty(QuizableSearchPanel.FIELD_NAME_PROPERTY, fieldName);

        panel.putClientProperty(QuizableSearchPanel.FIELD_PATH_PROPERTY, new ArrayList<>(fieldPath));

        panel.putClientProperty(QuizableSearchPanel.FIELD_VALUE_PROPERTY, value);

        return panel;
    }

    private static Set<Object> copyIdentitySet(Set<Object> original) {
        Set<Object> copy = Collections.newSetFromMap(new IdentityHashMap<>());

        if (original != null) {
            copy.addAll(original);
        }

        return copy;
    }
}