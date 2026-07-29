package quiz.ordering;

import objectview.Viewable;
import quiz.data.ViewableKeyExtractor;

/**
 * Extracts the hidden comparable value from a Viewable field path.
 */
public record OrderKey(String fieldPath, OrderValueType valueType) {
    private static final ViewableKeyExtractor EXTRACTOR = new ViewableKeyExtractor();
    public OrderKey {
        if (fieldPath == null || fieldPath.isBlank()) {
            throw new IllegalArgumentException("An ordering field path is required");
        }
        if (valueType == null) {
            throw new IllegalArgumentException("An ordering value type is required");
        }
    }

    public OrderValue extract(Viewable item) {
        java.util.List<Object> values = EXTRACTOR.alternatives(item, fieldPath);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException(
                    "Ordering field must resolve to one value: " + fieldPath);
        }
        return OrderValue.of(valueType, values.getFirst());
    }
}
