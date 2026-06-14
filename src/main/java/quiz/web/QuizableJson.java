package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.annotations.Link;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link QuizableView} from any {@link Quizable} by reflecting over
 * its fields, mirroring {@code QuizablePanel.addRenderedField}: scalars
 * become text/list, {@code @Link} becomes link, {@code @QuizableInline}
 * embeds nested views, and any other Quizable (single or in a
 * collection/map) becomes a lazy reference.
 */
public final class QuizableJson {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private QuizableJson() {}

    public static QuizableView of(Quizable q) {
        return of(q, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    public static String json(Quizable q) {
        try {
            return MAPPER.writeValueAsString(of(q));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize " + q, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private static QuizableView of(Quizable q, Set<Object> visited) {
        String id = q.getIdentifier();
        String name = q.getDisplayName();
        String type = q.getClass().getSimpleName();

        // Cycle guard: a Quizable already on the current path renders shallow.
        if (!visited.add(q)) {
            return new QuizableView(id, name, type, List.of());
        }

        try {
            List<QuizableView.Field> fields = new ArrayList<>();

            for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
                if ("name".equals(f.getName())) {
                    continue;
                }

                Object value;
                try {
                    value = f.get(q);
                } catch (Exception e) {
                    continue;
                }

                if (!QuizableAdapter.isValidQuizValue(value)) {
                    continue;
                }

                QuizableView.Field field = field(f, value, visited);
                if (field != null) {
                    fields.add(field);
                }
            }

            return new QuizableView(id, name, type, fields);
        } finally {
            visited.remove(q);
        }
    }

    private static QuizableView.Field field(Field f, Object value, Set<Object> visited) {
        String name = f.getName();

        if (QuizableAdapter.isLinkField(f) && value instanceof String s && !s.isBlank()) {
            Link link = f.getAnnotation(Link.class);
            return linkField(name, s, link == null ? "" : link.text());
        }

        if (QuizableAdapter.isQuizableInline(f)) {
            List<QuizableView> nodes = inlineNodes(value, visited);
            return nodes.isEmpty() ? null : QuizableView.Field.inline(name, nodes);
        }

        if (value instanceof Quizable q) {
            return QuizableView.Field.ref(name, ref(q));
        }

        if (value instanceof Collection<?> c) {
            return collectionField(name, c);
        }

        if (value instanceof Map<?, ?> m) {
            return collectionField(name, m.values());
        }

        return QuizableView.Field.text(name, String.valueOf(value));
    }

    private static QuizableView.Field collectionField(String name, Collection<?> items) {
        List<QuizableView.Ref> refs = new ArrayList<>();

        for (Object item : items) {
            if (item instanceof Quizable q) {
                refs.add(ref(q));
            }
        }

        if (!refs.isEmpty()) {
            return QuizableView.Field.refs(name, refs);
        }

        List<String> values = new ArrayList<>();
        for (Object item : items) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }

        return values.isEmpty() ? null : QuizableView.Field.list(name, values);
    }

    private static List<QuizableView> inlineNodes(Object value, Set<Object> visited) {
        List<QuizableView> nodes = new ArrayList<>();

        if (value instanceof Quizable q) {
            nodes.add(of(q, visited));
        } else if (value instanceof Collection<?> c) {
            for (Object item : c) {
                if (item instanceof Quizable q) {
                    nodes.add(of(q, visited));
                }
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                if (item instanceof Quizable q) {
                    nodes.add(of(q, visited));
                }
            }
        }

        return nodes;
    }

    private static QuizableView.Field linkField(String name, String rawValue, String annotationText) {
        String label = null;
        String url = rawValue;

        int bar = rawValue.indexOf('|');
        if (bar > 0 && bar < rawValue.length() - 1) {
            label = rawValue.substring(0, bar).trim();
            url = rawValue.substring(bar + 1).trim();
        }

        if (annotationText != null && !annotationText.isBlank()) {
            label = annotationText.trim();
        }

        return QuizableView.Field.link(name, label == null ? url : label, url);
    }

    private static QuizableView.Ref ref(Quizable q) {
        return new QuizableView.Ref(
                q.getIdentifier(), q.getDisplayName(), q.getClass().getSimpleName());
    }
}
