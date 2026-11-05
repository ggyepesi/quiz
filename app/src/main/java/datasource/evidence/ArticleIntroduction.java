package datasource.evidence;

import java.util.List;

/** The introduction of one versioned article, before a field recipe interprets it. */
public record ArticleIntroduction(String text, SourceDocument document, List<Link> links) {
    /** One internal article link, positioned in {@link ArticleIntroduction#text()}. */
    public record Link(String text, String targetTitle, int start, int end) {
        public Link {
            text = text == null ? "" : text.trim();
            targetTitle = targetTitle == null ? "" : targetTitle.trim();
            if (text.isBlank() || targetTitle.isBlank()) {
                throw new IllegalArgumentException("Article link text and target are required");
            }
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("Article link needs a non-empty text range");
            }
        }
    }

    public ArticleIntroduction(String text, SourceDocument document) {
        this(text, document, List.of());
    }

    public ArticleIntroduction {
        text = text == null ? "" : text.trim();
        document = java.util.Objects.requireNonNull(document, "Article document is required");
        links = links == null ? List.of() : List.copyOf(links);
    }
}
