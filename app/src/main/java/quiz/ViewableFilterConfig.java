package quiz;

import objectview.Viewable;
import objectview.ViewableAdapter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ViewableFilterConfig {
    private final List<ViewableFieldOperation> operations =
            new ArrayList<>();

    private final Map<Class<?>, Map<String, Field>> fieldCache =
            new ConcurrentHashMap<>();

    public void addOperation(ViewableFieldOperation op) {
        if (op != null) {
            operations.add(op);
        }
    }

    public List<ViewableFieldOperation> getOperations() {
        return operations;
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    public boolean matches(Viewable q) {
        if (q == null) {
            return false;
        }

        for (ViewableFieldOperation op : operations) {
            Object value = extractValue(q, op.getPath());

            if (!matchesOperation(value, op)) {
                return false;
            }
        }

        return true;
    }

    private boolean matchesOperation(
            Object value,
            ViewableFieldOperation op
    ) {
        return switch (op.getKind()) {
            case EXISTS -> ViewableAdapter.isValidQuizValue(value);
            case EMPTY -> !ViewableAdapter.isValidQuizValue(value);
            case CONTAINS -> contains(value, op.getArgument());
            case EQUALS -> equalsValue(value, op.getArgument());
            case NOT_EQUALS -> !equalsValue(value, op.getArgument());
            case GREATER_THAN -> compare(value, op.getArgument()) > 0;
            case LESS_THAN -> compare(value, op.getArgument()) < 0;
            case GREATER_OR_EQUAL -> compare(value, op.getArgument()) >= 0;
            case LESS_OR_EQUAL -> compare(value, op.getArgument()) <= 0;
        };
    }

    private boolean contains(Object value, String arg) {
        if (value == null || arg == null) {
            return false;
        }

        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                if (contains(item, arg)) {
                    return true;
                }
            }

            return false;
        }

        if (value instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                if (contains(item, arg)) {
                    return true;
                }
            }

            return false;
        }

        return normalize(toText(value)).contains(normalize(arg));
    }

    private boolean equalsValue(Object value, String arg) {
        if (value == null || arg == null) {
            return false;
        }

        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                if (equalsValue(item, arg)) {
                    return true;
                }
            }

            return false;
        }

        if (value instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                if (equalsValue(item, arg)) {
                    return true;
                }
            }

            return false;
        }

        return normalize(toText(value)).equals(normalize(arg));
    }

    private int compare(Object value, String arg) {
        Double left = firstNumber(value);
        Double right = parseNumber(arg);

        if (left != null && right != null) {
            return Double.compare(left, right);
        }

        return normalize(toText(value)).compareTo(normalize(arg));
    }

    private Double firstNumber(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number n) {
            return n.doubleValue();
        }

        if (value instanceof Collection<?> c) {
            for (Object item : c) {
                Double d = firstNumber(item);

                if (d != null) {
                    return d;
                }
            }

            return null;
        }

        if (value instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                Double d = firstNumber(item);

                if (d != null) {
                    return d;
                }
            }

            return null;
        }

        return parseFirstNumber(toText(value));
    }

    private Double parseFirstNumber(String s) {
        if (s == null) {
            return null;
        }

        java.util.regex.Matcher m =
                java.util.regex.Pattern
                        .compile("\\d[\\d,\\. ]*")
                        .matcher(s);

        if (!m.find()) {
            return null;
        }

        return parseNumber(m.group());
    }

    private Double parseNumber(String s) {
        if (s == null) {
            return null;
        }

        String cleaned = s
                .replace(",", "")
                .replace(" ", "")
                .trim();

        try {
            return Double.parseDouble(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private String toText(Object value) {
        if (value == null) {
            return "";
        }

        if (value instanceof Viewable q) {
            return q.getName();
        }

        return String.valueOf(value);
    }

    private String normalize(String s) {
        return s == null
                ? ""
                : s.toLowerCase().trim();
    }

    private Object extractValue(Object obj, List<String> path) {
        try {
            return extractRecursive(obj, path, 0);
        } catch (Exception e) {
            return null;
        }
    }

    private Object extractRecursive(
            Object obj,
            List<String> path,
            int idx
    ) throws Exception {
        if (obj == null) {
            return null;
        }

        if (idx >= path.size()) {
            return obj;
        }

        if (obj instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();

            for (Object item : c) {
                Object v = extractRecursive(item, path, idx);

                if (v != null) {
                    out.add(v);
                }
            }

            return out.isEmpty() ? null : out;
        }

        if (obj instanceof Map<?, ?> m) {
            List<Object> out = new ArrayList<>();

            for (Object item : m.values()) {
                Object v = extractRecursive(item, path, idx);

                if (v != null) {
                    out.add(v);
                }
            }

            return out.isEmpty() ? null : out;
        }

        String part = path.get(idx);

        if ("name".equals(part) && obj instanceof Viewable q) {
            return q.getName();
        }

        Field f = getFieldCached(obj.getClass(), part);

        if (f == null) {
            return null;
        }

        Object next = f.get(obj);

        return extractRecursive(next, path, idx + 1);
    }

    private Field getFieldCached(Class<?> cls, String name) {
        Map<String, Field> map =
                fieldCache.computeIfAbsent(
                        cls,
                        k -> new ConcurrentHashMap<>()
                );

        return map.computeIfAbsent(
                name,
                key -> ViewableAdapter.getField(cls, key)
        );
    }
}
