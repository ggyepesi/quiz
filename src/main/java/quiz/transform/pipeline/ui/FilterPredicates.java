package quiz.transform.pipeline.ui;

import objectview.field.FieldAccess;

import java.util.Collection;
import java.util.Objects;

public final class FilterPredicates {

    private FilterPredicates() {}

    public static boolean matches(Object object, FilterCondition c) {
        Object actual = FieldAccess.getPath(object, c.field().field());

        return switch (c.operator()) {
            case EQUALS -> Objects.equals(normalize(actual), normalize(c.value()));
            case NOT_EQUALS -> !Objects.equals(normalize(actual), normalize(c.value()));
            case CONTAINS -> contains(actual, c.value());
            case NOT_CONTAINS -> !contains(actual, c.value());
            case SIZE_EQUALS -> sizeOf(actual) == intOf(c.value());
            case SIZE_GREATER_THAN -> sizeOf(actual) > intOf(c.value());
            case SIZE_LESS_THAN -> sizeOf(actual) < intOf(c.value());
            case STARTS_WITH -> string(actual).startsWith(string(c.value()));
            case ENDS_WITH -> string(actual).endsWith(string(c.value()));
            case LESS_THAN -> compare(actual, c.value()) < 0;
            case LESS_OR_EQUAL -> compare(actual, c.value()) <= 0;
            case GREATER_THAN -> compare(actual, c.value()) > 0;
            case GREATER_OR_EQUAL -> compare(actual, c.value()) >= 0;
            case BETWEEN -> compare(actual, c.value()) >= 0
                    && compare(actual, c.value2()) <= 0;
            case IS_TRUE -> Boolean.TRUE.equals(actual);
            case IS_FALSE -> Boolean.FALSE.equals(actual);
            case IS_EMPTY -> isEmpty(actual);
            case IS_NOT_EMPTY -> !isEmpty(actual);
        };
    }

    private static Object normalize(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v;
    }

    private static boolean contains(Object actual, Object needle) {
        if (actual instanceof Collection<?> c) {
            return c.stream().anyMatch(x ->
                                               Objects.equals(normalize(x), normalize(needle))
                                                       || string(x).contains(string(needle)));
        }
        return string(actual).contains(string(needle));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object a, Object b) {
        if (a instanceof Number an && b instanceof Number bn) {
            return Double.compare(an.doubleValue(), bn.doubleValue());
        }
        if (a instanceof Comparable ca && b != null
                && a.getClass().isAssignableFrom(b.getClass())) {
            return ca.compareTo(b);
        }
        return string(a).compareTo(string(b));
    }

    private static boolean isEmpty(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof String s) {
            return s.isBlank();
        }
        if (v instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (v instanceof java.util.Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
    }

    private static String string(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** The element count of a multi-valued field (0 for null; 1 for a lone value). */
    private static int sizeOf(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Collection<?> c) {
            return c.size();
        }
        if (v instanceof java.util.Map<?, ?> m) {
            return m.size();
        }
        if (v.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(v);
        }
        return 1;
    }

    /** Parse the size threshold — a Number or a numeric string (else 0). */
    private static int intOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(string(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}