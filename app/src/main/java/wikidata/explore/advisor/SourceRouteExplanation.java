package wikidata.explore.advisor;

import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.RuleDirection;

/** One ordered source step in the explanation of how a model element is built. */
public record SourceRouteExplanation(
        int priority,
        FieldSourceType sourceType,
        String propertyId,
        String propertyLabel,
        RuleDirection direction,
        String example,
        boolean fallback) {

    public SourceRouteExplanation {
        priority = Math.max(1, priority);
        sourceType = sourceType == null ? FieldSourceType.SPARQL : sourceType;
        propertyId = clean(propertyId);
        propertyLabel = clean(propertyLabel);
        direction = direction == null ? RuleDirection.ITEM_TO_ROOT : direction;
        example = clean(example);
    }

    public String displayProperty() {
        if (propertyId.isBlank()) {
            return propertyLabel.isBlank() ? "(not configured)" : propertyLabel;
        }
        return propertyLabel.isBlank()
                ? propertyId
                : propertyLabel + " (" + propertyId + ")";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
