package quiz.ui;

import quiz.*;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-UI helper for QuizableSearchPanel.
 *
 * It owns:
 * - cached field extraction
 * - search index
 * - token matching
 * - decorated/precomputed sort keys
 *
 * It deliberately does NOT own:
 * - Swing highlighting
 * - dialogs
 * - navigation result rows
 */
public class QuizablePanelSearchAndSort {

    private final Map<Class<?>, Map<String, Field>> fieldCache =
            new ConcurrentHashMap<>();

    private final List<SearchEntry> searchIndex =
            new ArrayList<>();

    public void rebuildSearchIndex(
            JPanel targetPanel,
            QuizablePanelConfig searchConfig) {

        searchIndex.clear();

        if (targetPanel == null || searchConfig == null) {
            return;
        }

        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(
                        searchConfig,
                        QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

        for (Component c : targetPanel.getComponents()) {
            if (!(c instanceof QuizablePanel qp)) {
                continue;
            }

            Map<String, String> fieldTextByTitle =
                    new LinkedHashMap<>();

            for (QuizableFieldPaths.FieldPath fp : paths) {
                Object value =
                        extractValue(qp.getQuizable(), fp.path());

                fieldTextByTitle.put(
                        fp.title(),
                        normalize(flattenForSearch(value)));
            }

            searchIndex.add(new SearchEntry(qp, fieldTextByTitle));
        }
    }

    public Map<String, List<QuizablePanel>> search(
            List<String> queryTokens) {

        Map<String, List<QuizablePanel>> out =
                new LinkedHashMap<>();

        if (queryTokens == null || queryTokens.isEmpty()) {
            return out;
        }

        for (SearchEntry entry : searchIndex) {
            for (Map.Entry<String, String> field
                    : entry.fieldTextByTitle.entrySet()) {

                if (!containsAllTokens(field.getValue(), queryTokens)) {
                    continue;
                }

                out.computeIfAbsent(
                        field.getKey(),
                        k -> new ArrayList<>())
                   .add(entry.panel);
            }
        }

        return out;
    }

    public List<QuizablePanel> sortPanels(
            List<QuizablePanel> panels,
            List<QuizableFieldPaths.FieldPath> sortPaths) {

        List<PanelSortKey> keyed =
                new ArrayList<>();

        for (QuizablePanel panel : panels) {
            keyed.add(new PanelSortKey(
                    panel,
                    buildSortKey(panel, sortPaths)));
        }

        keyed.sort(Comparator.comparing(PanelSortKey::key));

        List<QuizablePanel> out =
                new ArrayList<>(keyed.size());

        for (PanelSortKey key : keyed) {
            out.add(key.panel);
        }

        return out;
    }

    private String buildSortKey(
            QuizablePanel panel,
            List<QuizableFieldPaths.FieldPath> paths) {

        StringBuilder sb =
                new StringBuilder();

        for (QuizableFieldPaths.FieldPath f : paths) {
            Object value =
                    extractValue(panel.getQuizable(), f.path());

            sb.append(sortableString(value))
              .append('\u0000');
        }

        sb.append(sortableString(panel.getQuizable()));

        return sb.toString();
    }

    private Object extractValue(
            Object obj,
            List<String> path) {

        try {
            return extractRecursive(obj, path, 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object extractRecursive(
            Object obj,
            List<String> path,
            int idx) throws Exception {

        if (obj == null) {
            return null;
        }

        if (idx >= path.size()) {
            return obj;
        }

        if (obj instanceof Collection<?> c) {
            List<Object> out =
                    new ArrayList<>();

            for (Object item : c) {
                Object v =
                        extractRecursive(item, path, idx);

                if (v != null) {
                    out.add(v);
                }
            }

            return out.isEmpty() ? null : out;
        }

        if (obj instanceof Map<?, ?> m) {
            List<Object> out =
                    new ArrayList<>();

            for (Object v : m.values()) {
                Object vv =
                        extractRecursive(v, path, idx);

                if (vv != null) {
                    out.add(vv);
                }
            }

            return out.isEmpty() ? null : out;
        }

        String part =
                path.get(idx);

        if ("name".equals(part) && obj instanceof Quizable q) {
            return q.getName();
        }

        Field f =
                getFieldCached(obj.getClass(), part);

        if (f == null) {
            return null;
        }

        Object next =
                f.get(obj);

        return extractRecursive(next, path, idx + 1);
    }

    private Field getFieldCached(
            Class<?> cls,
            String name) {

        Map<String, Field> map =
                fieldCache.computeIfAbsent(
                        cls,
                        k -> new ConcurrentHashMap<>());

        return map.computeIfAbsent(
                name,
                key -> QuizableAdapter.getField(cls, key));
    }

    private String flattenForSearch(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof Quizable q) {
            return q.getName();
        }

        if (value instanceof Collection<?> c) {
            StringBuilder sb =
                    new StringBuilder();

            for (Object item : c) {
                sb.append(flattenForSearch(item))
                  .append(' ');
            }

            return sb.toString();
        }

        if (value instanceof Map<?, ?> m) {
            StringBuilder sb =
                    new StringBuilder();

            for (Object item : m.values()) {
                sb.append(flattenForSearch(item))
                  .append(' ');
            }

            return sb.toString();
        }

        return String.valueOf(value);
    }

    private boolean containsAllTokens(
            String haystack,
            List<String> tokens) {

        if (haystack == null) {
            return false;
        }

        for (String token : tokens) {
            if (!haystack.contains(token)) {
                return false;
            }
        }

        return true;
    }

    private String sortableString(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof Quizable q) {
            return normalize(q.getName());
        }

        if (value instanceof Collection<?> c) {
            return c.stream()
                    .map(this::toSortable)
                    .filter(s -> !s.isBlank())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .findFirst()
                    .orElse("");
        }

        if (value instanceof Map<?, ?> m) {
            return m.values().stream()
                    .map(this::toSortable)
                    .filter(s -> !s.isBlank())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .findFirst()
                    .orElse("");
        }

        return normalize(String.valueOf(value));
    }

    private String toSortable(Object o) {
        if (o == null) {
            return "";
        }

        return o instanceof Quizable q
                ? normalize(q.getName())
                : normalize(String.valueOf(o));
    }

    private String normalize(String s) {
        return s == null ? "" : s.toLowerCase().trim();
    }

    private record SearchEntry(
            QuizablePanel panel,
            Map<String, String> fieldTextByTitle) {
    }

    private record PanelSortKey(
            QuizablePanel panel,
            String key) {
    }
}
