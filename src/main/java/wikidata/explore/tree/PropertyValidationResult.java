package wikidata.explore.tree;

public record PropertyValidationResult(
        boolean valid,
        String pid,
        String label,
        String description,
        String wikibaseType,
        String recommendedFieldType,
        String message
) {}
