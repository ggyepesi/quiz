package wikidata.explore.model;

import objectview.Viewable;

import java.util.Collection;

/**
 * Evaluates a {@link CanonicalSpec} against an object's fields to produce its
 * {@code displayName} and {@code identifier}. Pure: it reads field values through
 * a {@link FieldReader} (e.g. {@code wdo::get}) and never touches Swing or IO.
 * See docs/canonicalization-model.md.
 */
public final class Canonicalizer {

    private Canonicalizer() {}

    /** Reads a field's raw value by name (returns null when absent). */
    public interface FieldReader {
        Object read(String fieldName);
    }

    /**
     * The displayName per the spec: {@code LABEL} keeps the object's existing
     * label ({@code fallbackLabel}); {@code FIELD} uses one single-valued field's
     * label; {@code TEMPLATE} interpolates {@code {field}} references. Falls back
     * to {@code fallbackLabel} when the rule resolves to blank.
     */
    public static String displayName(CanonicalSpec spec, FieldReader reader, String fallbackLabel) {
        if (spec == null) {
            return safe(fallbackLabel);
        }
        return switch (spec.displayNameMode()) {
            case LABEL -> safe(fallbackLabel);
            case FIELD -> orFallback(labelOf(read(reader, spec.displayNameField())), fallbackLabel);
            case TEMPLATE -> orFallback(interpolate(spec.displayNameTemplate(), reader), fallbackLabel);
        };
    }

    /**
     * The identifier for a class of {@code kind}: one whose identity comes from its
     * source keeps its {@code qid}; one that derives it joins its natural-key fields
     * (the grain). Falls back to {@code fallback} when nothing resolves.
     *
     * <p>The regime is the caller's to state, because it follows from how the class is
     * built rather than from anything in the spec.
     */
    public static String identifier(ClassKind kind, CanonicalSpec spec,
                                    FieldReader reader, String qid, String fallback) {
        if (spec == null || kind == null || kind.identityFromSource()) {
            return firstNonBlank(qid, fallback);
        }
        if (spec.keyFields().isEmpty()) {
            return safe(fallback);
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (String key : spec.keyFields()) {
            String id = idOf(read(reader, key));
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(id);
            any |= !id.isBlank();
        }
        return any ? sb.toString() : safe(fallback);
    }

    private static Object read(FieldReader reader, String field) {
        return reader == null || field == null || field.isBlank() ? null : reader.read(field);
    }

    private static String interpolate(String template, FieldReader reader) {
        if (template == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i) {
                    String field = template.substring(i + 1, end).trim();
                    out.append(labelOf(read(reader, field)));
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString().trim();
    }

    // A value's label: a reference resolves via its displayName; a single-element
    // collection via its element; a scalar via toString.
    private static String labelOf(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Viewable q) {
            return safe(q.getDisplayName());
        }
        if (v instanceof Collection<?> c) {
            return c.isEmpty() ? "" : labelOf(c.iterator().next());
        }
        return String.valueOf(v);
    }

    private static String idOf(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Viewable q) {
            return safe(q.getIdentifier());
        }
        if (v instanceof Collection<?> c) {
            return c.isEmpty() ? "" : idOf(c.iterator().next());
        }
        return String.valueOf(v);
    }

    private static String orFallback(String value, String fallback) {
        return value == null || value.isBlank() ? safe(fallback) : value;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : safe(b);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
