package quiz.transform.ui;

/** One {@code field == value} condition of a FILTER predicate. The predicate is
 *  the conjunction (AND) of its conditions. */
public record FilterCondition(DomainField field, Object value) {
}
