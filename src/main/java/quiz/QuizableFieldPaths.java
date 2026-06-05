package quiz;

import quiz.ui.ImagePane;

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

        return out;
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

    private static void collectField(Field field,
                                     QuizablePanelConfig childConfig,
                                     List<String> prefix,
                                     String titlePrefix,
                                     FieldFilter filter,
                                     List<FieldPath> out) {
        if (field == null || !filter.accept(field)) {
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