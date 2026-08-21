package datasource.evidence;

/** Versioned membership of one source page in one category. */
public record CategoryMembership(String category, SourceDocument document) {
    public CategoryMembership {
        category = category == null ? "" : category.trim();
        document = java.util.Objects.requireNonNull(document);
        if (category.isBlank()) throw new IllegalArgumentException("Category is required");
    }
}
