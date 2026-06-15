package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import quiz.ImageRef;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.annotations.Link;

import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    /** Render-model for a single named field of {@code owner}, or null. */
    public static QuizableView.Field fieldOf(Quizable owner, String fieldName) {
        // "name" is the display name (skipped as a normal field, but usable
        // as a quiz prompt/answer).
        if ("name".equals(fieldName)) {
            String dn = owner.getDisplayName();
            return dn == null || dn.isBlank() ? null : QuizableView.Field.text("name", dn);
        }

        Field f = QuizableAdapter.getField(owner.getClass(), fieldName);
        if (f == null) {
            return null;
        }

        Object value;
        try {
            f.setAccessible(true);
            value = f.get(owner);
        } catch (Exception e) {
            return null;
        }

        if (!QuizableAdapter.isValidQuizValue(value)) {
            return null;
        }

        return field(
                owner.getClass().getSimpleName(),
                owner.getIdentifier(),
                f,
                value,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * A plain string value of a field for use as a quiz answer/option:
     * Quizable -> display name, collection/map -> joined items, else the
     * value's string. Null if empty.
     */
    public static String stringValue(Quizable owner, String fieldName) {
        if ("name".equals(fieldName)) {
            String dn = owner.getDisplayName();
            return dn == null || dn.isBlank() ? null : dn;
        }

        Field f = QuizableAdapter.getField(owner.getClass(), fieldName);
        if (f == null) {
            return null;
        }

        try {
            f.setAccessible(true);
            Object v = f.get(owner);
            String s = asString(v);
            return s == null || s.isBlank() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Quizable q) {
            return q.getDisplayName();
        }
        if (v instanceof Collection<?> c) {
            return joinItems(c);
        }
        if (v instanceof Map<?, ?> m) {
            return joinItems(m.values());
        }
        return String.valueOf(v);
    }

    private static String joinItems(Collection<?> items) {
        List<String> parts = new ArrayList<>();
        for (Object item : items) {
            String s = item instanceof Quizable q ? q.getDisplayName() : String.valueOf(item);
            if (s != null && !s.isBlank()) {
                parts.add(s);
            }
        }
        return String.join(", ", parts);
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

                QuizableView.Field field = field(type, id, f, value, visited);
                if (field != null) {
                    fields.add(field);
                }
            }

            return new QuizableView(id, name, type, fields);
        } finally {
            visited.remove(q);
        }
    }

    private static QuizableView.Field field(
            String ownerType, String ownerId, Field f, Object value, Set<Object> visited) {

        String name = f.getName();

        if (value instanceof ImageRef) {
            String url = "/api/image/"
                    + enc(ownerType) + "/" + enc(ownerId) + "/" + enc(name);
            return QuizableView.Field.image(name, url);
        }

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
            return collectionField(ownerType, ownerId, name, c);
        }

        if (value instanceof Map<?, ?> m) {
            return collectionField(ownerType, ownerId, name, m.values());
        }

        return QuizableView.Field.text(name, String.valueOf(value));
    }

    private static QuizableView.Field collectionField(
            String ownerType, String ownerId, String name, Collection<?> items) {

        // A collection of images (e.g. flag versions): one indexed image URL
        // per item, by position in the collection.
        List<String> imageUrls = new ArrayList<>();
        int idx = 0;
        for (Object item : items) {
            if (item instanceof ImageRef) {
                imageUrls.add("/api/image/"
                        + enc(ownerType) + "/" + enc(ownerId) + "/" + enc(name) + "/" + idx);
            }
            idx++;
        }
        if (!imageUrls.isEmpty()) {
            return QuizableView.Field.images(name, imageUrls);
        }

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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
