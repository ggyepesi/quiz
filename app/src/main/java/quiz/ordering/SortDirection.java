package quiz.ordering;

public enum SortDirection {
    ASCENDING,
    DESCENDING;

    int apply(int comparison) {
        return this == ASCENDING ? comparison : -comparison;
    }
}
