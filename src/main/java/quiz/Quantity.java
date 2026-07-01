package quiz;

/**
 * A measured value with its unit (e.g. {@code 1538 K}, {@code 7.874 g/cm³}).
 *
 * <p>Renders as "{amount} {unit}" (just the number when unit-less), but keeps
 * the numeric {@link #amount()} so it can sort/compare numerically. Wikidata's
 * truthy values drop the unit; the extractor fetches it from the statement's
 * value node and wraps both here.
 */
public final class Quantity implements Comparable<Quantity> {

    private final double amount;
    private final String unit;   // symbol, e.g. "K"; "" when dimensionless

    public Quantity(double amount, String unit) {
        this.amount = amount;
        this.unit = unit == null ? "" : unit.trim();
    }

    public double amount() {
        return amount;
    }

    public String unit() {
        return unit;
    }

    /** Tidy number: drops a trailing ".0" but keeps real decimals. */
    public static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    @Override
    public String toString() {
        String n = format(amount);
        return unit.isEmpty() ? n : n + " " + unit;
    }

    @Override
    public int compareTo(Quantity o) {
        return Double.compare(amount, o == null ? Double.NaN : o.amount);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Quantity q
                && Double.compare(q.amount, amount) == 0
                && unit.equals(q.unit);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(amount) * 31 + unit.hashCode();
    }
}
