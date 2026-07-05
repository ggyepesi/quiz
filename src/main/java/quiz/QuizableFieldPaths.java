package quiz;

import quiz.ui.ImagePane;
import quiz.ui.viewconfig.QuizablePanelConfig;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

public final class QuizableFieldPaths {
    private QuizableFieldPaths() {}

    public record FieldPath(String title, List<String> path, Field leafField) {}

    public interface FieldFilter {
        boolean accept(Field field);
    }

    public static final FieldFilter ALL_FIELDS = field -> true;

    public static final FieldFilter NOT_IMAGE_PANE_FIELDS =
            field -> field != null && !ImagePane.class.isAssignableFrom(field.getType());

    public static List<FieldPath> collect(QuizablePanelConfig config) {
        return collect(config, NOT_IMAGE_PANE_FIELDS);
    }

    public static List<FieldPath> collect(QuizablePanelConfig config,
                                          FieldFilter filter) {
        List<FieldPath> out = new ArrayList<>();

        if (config == null || config.getCls() == null) {
            return out;
        }

        if (!Quizable.class.isAssignableFrom(config.getCls())) {
            return out;
        }

        collect(
                config,
                config.getCls(),
                new ArrayList<>(),
                "",
                filter == null ? ALL_FIELDS : filter,
                out);

        ensureIdentityFields(config.getCls(), out);

        return dedupByPath(out);
    }

    /**
     * Keeps the first {@link FieldPath} for each distinct access path, dropping
     * later duplicates. Two entries with the same path address the same value, so
     * surfacing both only lets a stray/duplicated field (classically a second
     * {@code name}, before canonicalization) double the identity in sort/search/
     * config — with an inconsistent composite sort key as the symptom. The first
     * occurrence wins, so the canonical/identity entry (emitted first) is the one
     * kept. See docs/canonicalization-model.md.
     */
    static List<FieldPath> dedupByPath(List<FieldPath> paths) {
        List<FieldPath> out = new ArrayList<>();
        Set<List<String>> seen = new LinkedHashSet<>();
        for (FieldPath p : paths) {
            if (p != null && seen.add(p.path())) {
                out.add(p);
            }
        }
        return out;
    }

    /** How deep to follow references when enumerating from a sample instance. */
    private static final int SAMPLE_MAX_DEPTH = 2;

    /**
     * Field paths enumerated from a SAMPLE INSTANCE — so a {@link DynamicFields}
     * object (a snapshot WDO, a DynamicQuizable) whose fields live in a property map
     * (not declared Java fields) still yields its fields, with nested paths followed
     * through reference values up to {@link #SAMPLE_MAX_DEPTH}. A reflection Quizable
     * falls back to its declared fields. Branch-cycle-safe. This makes the nested,
     * typed field model work over dynamic domains, not just reflected ones.
     */
    public static List<FieldPath> collectFromSample(Quizable sample, FieldFilter filter) {
        List<FieldPath> out = new ArrayList<>();
        if (sample == null) {
            return out;
        }
        out.add(new FieldPath("name", List.of("name"), null));   // identity/display
        collectSample(sample, new ArrayList<>(), "",
                filter == null ? ALL_FIELDS : filter,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()),
                out);
        return dedupByPath(out);
    }

    private static void collectSample(Quizable obj, List<String> prefix, String titlePrefix,
                                      FieldFilter filter, Set<Object> branch, List<FieldPath> out) {
        if (obj == null || !branch.add(obj)) {
            return;
        }
        try {
            if (obj instanceof DynamicFields dyn) {
                for (Map.Entry<String, Object> e : dyn.dynamicFieldValues().entrySet()) {
                    addSampleField(e.getKey(), e.getValue(), null,
                            prefix, titlePrefix, filter, branch, out);
                }
            } else {
                for (Field field : QuizableAdapter.getAllFields(obj.getClass())) {
                    if (!filter.accept(field) || QuizableAdapter.isProvenanceField(field)) {
                        continue;
                    }
                    Object v;
                    try {
                        field.setAccessible(true);
                        v = field.get(obj);
                    } catch (Exception ex) {
                        v = null;
                    }
                    addSampleField(field.getName(), v, field,
                            prefix, titlePrefix, filter, branch, out);
                }
            }
        } finally {
            branch.remove(obj);
        }
    }

    private static void addSampleField(String name, Object value, Field leaf,
                                       List<String> prefix, String titlePrefix,
                                       FieldFilter filter, Set<Object> branch, List<FieldPath> out) {
        List<String> path = new ArrayList<>(prefix);
        path.add(name);
        String title = titlePrefix.isEmpty() ? name : titlePrefix + "." + name;

        Quizable child = firstQuizable(value);
        if (child != null) {
            // A reference: offer the reference ITSELF (for invert / group-by-
            // reference), its display name, and (bounded) its nested fields.
            out.add(new FieldPath(title, path, leaf));
            List<String> namePath = new ArrayList<>(path);
            namePath.add("name");
            out.add(new FieldPath(title + ".name", namePath, leaf));
            if (prefix.size() < SAMPLE_MAX_DEPTH) {
                collectSample(child, path, title, filter, branch, out);
            }
        } else {
            out.add(new FieldPath(title, path, leaf));
        }
    }

    private static Quizable firstQuizable(Object v) {
        if (v instanceof Quizable q) {
            return q;
        }
        if (v instanceof Collection<?> c) {
            for (Object i : c) {
                if (i instanceof Quizable q) return q;
            }
        }
        if (v instanceof Map<?, ?> m) {
            for (Object i : m.values()) {
                if (i instanceof Quizable q) return q;
            }
        }
        return null;
    }

    private static void collect(QuizablePanelConfig config,
                                Class<?> cls,
                                List<String> prefix,
                                String titlePrefix,
                                FieldFilter filter,
                                List<FieldPath> out) {
        if (config == null || cls == null || !Quizable.class.isAssignableFrom(cls)) {
            return;
        }

        Set<String> alreadyAdded = new LinkedHashSet<>();

        // 1. Explicit fields first, in config/editor order.
        for (Map.Entry<String, QuizablePanelConfig> e : config.getFields().entrySet()) {
            String fieldName = e.getKey();

            if ("name".equals(fieldName)) {
                addNamePath(prefix, titlePrefix, out);
                alreadyAdded.add("name");
                continue;
            }

            Field field = QuizableAdapter.getField(cls, fieldName);

            if (field != null && filter.accept(field)) {
                collectField(
                        field,
                        e.getValue(),
                        prefix,
                        titlePrefix,
                        filter,
                        out);

                alreadyAdded.add(fieldName);
            }
        }

        // 2. Add implicit allFields/allMinorFields only after explicit fields.
        for (Field field : QuizableAdapter.getAllFields(cls)) {
            String fieldName = field.getName();

            if (alreadyAdded.contains(fieldName)) {
                continue;
            }

            if ("name".equals(fieldName)) {
                if ((config.isAllFields() || config.getFields().containsKey("name"))
                        && !alreadyAdded.contains("name")) {
                    addNamePath(prefix, titlePrefix, out);
                    alreadyAdded.add("name");
                }
                continue;
            }

            if (!config.showsField(field)) {
                continue;
            }

            collectField(
                    field,
                    config.getFieldConfig(fieldName),
                    prefix,
                    titlePrefix,
                    filter,
                    out);
        }
    }

    private static void addNamePath(List<String> prefix,
                                    String titlePrefix,
                                    List<FieldPath> out) {
        List<String> namePath = new ArrayList<>(prefix);
        namePath.add("name");

        String title = titlePrefix.isEmpty()
                ? "name"
                : titlePrefix + ".name";

        out.add(new FieldPath(title, namePath, null));
    }

    // Identity fields (name + qid) are @NotQuizableField — hidden from the CARD
    // (they're the title/identity) but still meaningful to search/sort/configure
    // by. Without this a bare reference object (a WikidataDynamicObject with no
    // dynamic fields) offers nothing to configure. Scoped to entity objects (those
    // that declare a `qid` field) so non-Wikidata Quizables are untouched.
    private static void ensureIdentityFields(Class<?> cls, List<FieldPath> out) {
        Field qid = rawDeclaredField(cls, "qid");
        if (qid == null) {
            return;
        }
        if (!hasRootPath(out, "name")) {
            out.add(new FieldPath("name", List.of("name"), null));
        }
        if (!hasRootPath(out, "qid")) {
            qid.setAccessible(true);
            out.add(new FieldPath("qid", List.of("qid"), qid));
        }
    }

    private static boolean hasRootPath(List<FieldPath> out, String name) {
        for (FieldPath p : out) {
            if (p.path().size() == 1 && name.equals(p.path().get(0))) {
                return true;
            }
        }
        return false;
    }

    // Finds a declared field by name up the hierarchy, INCLUDING @NotQuizableField
    // ones (which QuizableAdapter.getField deliberately omits).
    private static Field rawDeclaredField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static void collectField(Field field,
                                     QuizablePanelConfig childConfig,
                                     List<String> prefix,
                                     String titlePrefix,
                                     FieldFilter filter,
                                     List<FieldPath> out) {
        if (field == null || !filter.accept(field)) {
            return;
        }
        // Provenance (the Source chip) is metadata, not a searchable/sortable
        // domain field — keep it out of the field paths.
        if (QuizableAdapter.isProvenanceField(field)) {
            return;
        }

        String fieldName = field.getName();

        List<String> path = new ArrayList<>(prefix);
        path.add(fieldName);

        String title = titlePrefix.isEmpty()
                ? fieldName
                : titlePrefix + "." + fieldName;

        Class<?> nested = nestedQuizableClass(field);

        if (nested != null) {
            if (childConfig != null
                    && (childConfig.isAllFields()
                    || childConfig.isAllMinorFields()
                    || !childConfig.getFields().isEmpty())) {

                QuizablePanelConfig child = childConfig.copy();
                child.setCls(asQuizableClass(nested));

                collect(
                        child,
                        nested,
                        path,
                        title,
                        filter,
                        out);
            } else {
                List<String> namePath = new ArrayList<>(path);
                namePath.add("name");

                out.add(new FieldPath(
                        title + ".name",
                        namePath,
                        field));
            }

            return;
        }

        out.add(new FieldPath(title, path, field));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Quizable> asQuizableClass(Class<?> cls) {
        return (Class<? extends Quizable>) cls;
    }

    @SuppressWarnings("unchecked")
    public static Class<? extends Quizable> nestedQuizableClass(Field field) {
        if (field == null) {
            return null;
        }

        Class<?> type = field.getType();

        if (ImagePane.class.isAssignableFrom(type)) {
            return null;
        }

        if (Quizable.class.isAssignableFrom(type)) {
            return (Class<? extends Quizable>) type;
        }

        if (Collection.class.isAssignableFrom(type)) {
            Type g = field.getGenericType();

            if (g instanceof ParameterizedType pt) {
                Type arg = pt.getActualTypeArguments()[0];

                if (arg instanceof Class<?> c && Quizable.class.isAssignableFrom(c)) {
                    return (Class<? extends Quizable>) c;
                }
            }

            return null;
        }

        if (Map.class.isAssignableFrom(type)) {
            Type g = field.getGenericType();

            if (g instanceof ParameterizedType pt) {
                Type value = pt.getActualTypeArguments()[1];

                if (value instanceof Class<?> c && Quizable.class.isAssignableFrom(c)) {
                    return (Class<? extends Quizable>) c;
                }
            }

            return null;
        }

        return null;
    }
}