package quiz.transform;

import objectview.field.FieldAccess;

import java.util.Collection;
import java.util.Objects;

/** The single evaluator for interactive and saved filter transformations. */
public final class FilterPredicates {
    private FilterPredicates() { }

    public static boolean matches(Object object, FilterCondition condition) {
        if (condition == null || condition.field() == null) return false;
        return matches(object, condition.field().field(), condition.operator(),
                condition.value(), condition.value2());
    }

    public static boolean matches(Object object, String fieldPath, FilterOperator operator,
                                  Object value, Object value2) {
        Object actual = FieldAccess.getPath(object, fieldPath);
        FilterOperator op = Objects.requireNonNull(operator, "operator");
        return switch (op) {
            case EQUALS -> Objects.equals(normalize(actual), normalize(value));
            case NOT_EQUALS -> !Objects.equals(normalize(actual), normalize(value));
            case CONTAINS -> contains(actual, value);
            case NOT_CONTAINS -> !contains(actual, value);
            case SIZE_EQUALS -> sizeOf(actual) == intOf(value);
            case SIZE_GREATER_THAN -> sizeOf(actual) > intOf(value);
            case SIZE_LESS_THAN -> sizeOf(actual) < intOf(value);
            case STARTS_WITH -> string(actual).startsWith(string(value));
            case ENDS_WITH -> string(actual).endsWith(string(value));
            case LESS_THAN -> compare(actual, value) < 0;
            case LESS_OR_EQUAL -> compare(actual, value) <= 0;
            case GREATER_THAN -> compare(actual, value) > 0;
            case GREATER_OR_EQUAL -> compare(actual, value) >= 0;
            case BETWEEN -> compare(actual, value) >= 0 && compare(actual, value2) <= 0;
            case IS_TRUE -> Boolean.TRUE.equals(actual);
            case IS_FALSE -> Boolean.FALSE.equals(actual);
            case IS_EMPTY -> isEmpty(actual);
            case IS_NOT_EMPTY -> !isEmpty(actual);
        };
    }

    private static Object normalize(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Enum<?> enumeration) return enumeration.toString();
        return value;
    }

    private static boolean contains(Object actual, Object needle) {
        if (actual instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item ->
                    Objects.equals(normalize(item), normalize(needle))
                            || string(item).contains(string(needle)));
        }
        return string(actual).contains(string(needle));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (left instanceof Comparable comparable && right != null
                && left.getClass().isAssignableFrom(right.getClass())) {
            return comparable.compareTo(right);
        }
        return string(left).compareTo(string(right));
    }

    private static boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String text) return text.isBlank();
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof java.util.Map<?, ?> map) return map.isEmpty();
        return false;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int sizeOf(Object value) {
        if (value == null) return 0;
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof java.util.Map<?, ?> map) return map.size();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        return 1;
    }

    private static int intOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(string(value).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
