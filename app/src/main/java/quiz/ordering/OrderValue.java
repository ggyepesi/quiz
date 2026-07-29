package quiz.ordering;

import aux.FlexibleDate;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * A normalized, comparable quiz value. Values of different declared types are
 * deliberately not comparable: a bad configuration should fail during quiz
 * generation rather than create a surprising order.
 */
public final class OrderValue implements Comparable<OrderValue> {
    private final OrderValueType type;
    private final Comparable<?> value;
    private final String label;

    private OrderValue(OrderValueType type, Comparable<?> value, String label) {
        this.type = Objects.requireNonNull(type);
        this.value = Objects.requireNonNull(value);
        this.label = Objects.requireNonNull(label);
    }

    public static OrderValue parse(OrderValueType type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        return switch (type) {
            case DATE -> {
                FlexibleDate date = FlexibleDate.parse(text);
                if (date == null) {
                    throw new IllegalArgumentException("Not a date: " + raw);
                }
                yield new OrderValue(type, date, date.format());
            }
            case NUMBER -> {
                try {
                    BigDecimal number = new BigDecimal(text.replace(",", ""));
                    yield new OrderValue(type, number, text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Not a number: " + raw, e);
                }
            }
            // Deterministic case-insensitive ordering, not linguistic
            // collation. A configurable Collator is the future upgrade for
            // locale-sensitive alphabets such as Hungarian.
            case TEXT -> new OrderValue(type, text.toLowerCase(Locale.ROOT), text);
        };
    }

    public static OrderValue of(OrderValueType type, Object raw) {
        if (raw == null) {
            return null;
        }
        if (type == OrderValueType.DATE && raw instanceof FlexibleDate date) {
            return new OrderValue(type, date, date.format());
        }
        if (type == OrderValueType.NUMBER && raw instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            return new OrderValue(type, decimal, number.toString());
        }
        return parse(type, raw.toString());
    }

    public OrderValueType type() {
        return type;
    }

    public String label() {
        return label;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public int compareTo(OrderValue other) {
        if (other == null || type != other.type) {
            throw new IllegalArgumentException("Cannot compare " + type + " with "
                    + (other == null ? "null" : other.type));
        }
        return ((Comparable) value).compareTo(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OrderValue that
                && type == that.type && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return label;
    }
}
