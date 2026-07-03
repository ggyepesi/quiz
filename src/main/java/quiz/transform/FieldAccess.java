package quiz.transform;

import quiz.QuizableAdapter;

import java.lang.reflect.Field;
import java.util.*;

public final class FieldAccess {

    private FieldAccess() {}

    public static Object getPath(Object root, String path) {
        Object current = root;

        for (String part : split(path)) {
            if (current == null) {
                return null;
            }

            try {
                Field f = requireField(current.getClass(), part);
                current = f.get(current);
            } catch (Exception e) {
                throw new RuntimeException("Cannot read path " + path + " from " + root, e);
            }
        }

        return current;
    }

    public static void setPath(Object root, String path, Object value) {
        Object owner = owner(root, path);
        String leaf = split(path).getLast();

        try {
            Field f = requireField(owner.getClass(), leaf);
            f.set(owner, value);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set path " + path + " on " + root, e);
        }
    }

    public static void addToCollection(Object root, String fieldName, Object value) {
        if (root == null || value == null) {
            return;
        }

        try {
            Field f = requireField(root.getClass(), fieldName);
            Object current = f.get(root);

            Collection<Object> collection;
            if (current == null) {
                collection = new ArrayList<>();
                f.set(root, collection);
            } else if (current instanceof Collection<?> c) {
                @SuppressWarnings("unchecked")
                Collection<Object> cast = (Collection<Object>) c;
                collection = cast;
            } else {
                throw new IllegalStateException(
                        "Field is not a collection: " + root.getClass().getSimpleName() + "." + fieldName);
            }

            if (!collection.contains(value)) {
                collection.add(value);
            }

        } catch (Exception e) {
            throw new RuntimeException("Cannot add to collection field " + fieldName, e);
        }
    }

    private static Object owner(Object root, String path) {
        List<String> parts = split(path);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Empty path");
        }

        Object current = root;
        for (int i = 0; i < parts.size() - 1; i++) {
            if (current == null) {
                throw new IllegalStateException("Null owner while resolving " + path);
            }

            try {
                Field f = requireField(current.getClass(), parts.get(i));
                current = f.get(current);
            } catch (Exception e) {
                throw new RuntimeException("Cannot resolve owner for " + path, e);
            }
        }

        return current;
    }

    private static Field requireField(Class<?> cls, String name) {
        Field f = QuizableAdapter.getField(cls, name);
        if (f == null) {
            throw new IllegalArgumentException("No field " + cls.getName() + "." + name);
        }
        f.setAccessible(true);
        return f;
    }

    private static List<String> split(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Empty field path");
        }
        return Arrays.asList(path.split("\\."));
    }
}