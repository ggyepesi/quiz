package datasource.api;

/**
 * A source fact a prepared operation needs before it can acquire or interpret values.
 *
 * <p>The requirement is provider-neutral: orchestration adapters decide how a source
 * satisfies it (for example, Wikidata satisfies an article correspondence with a
 * sitelink). This keeps downstream planning from recognizing individual recipes.
 */
public record SourceInputRequirement(String sourceId, Kind kind) {
    public enum Kind { ARTICLE_CORRESPONDENCE }

    public SourceInputRequirement {
        sourceId = sourceId == null ? "" : sourceId.trim();
        if (sourceId.isBlank()) throw new IllegalArgumentException("sourceId is required");
        if (kind == null) throw new IllegalArgumentException("kind is required");
    }
}
