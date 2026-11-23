package domain.datasource.evidence;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Exact supporting text in a versioned document. Offsets use [start,end).
 *
 * <p>{@code links} are the destinations the source authored inside that text, such as a
 * Wikipedia table cell's article links. They belong to the retained source structure, so
 * a review shows them as the links the page shows rather than as flattened text.</p>
 */
public record EvidenceFragment(
        SourceDocument document,
        String section,
        int startOffset,
        int endOffset,
        String excerpt,
        List<Link> links) {

    /**
     * One authored link in the excerpt. {@code identifying} says that the source
     * structure placed this link on the value being proposed (for example, in a table's
     * configured display column), rather than elsewhere in the same evidence row.
     */
    public record Link(String text, String url,
                       @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean identifying) {
        public Link(String text, String url) {
            this(text, url, false);
        }

        public Link {
            text = text == null ? "" : text.trim();
            url = url == null ? "" : url.trim();
            if (url.isBlank()) throw new IllegalArgumentException("Evidence link URL is required");
            if (text.isBlank()) text = url;
        }
    }

    public EvidenceFragment {
        links = links == null ? List.of() : links.stream()
                .filter(java.util.Objects::nonNull).toList();
        if (document == null) throw new IllegalArgumentException("Evidence document is required");
        section = section == null ? "" : section.trim();
        excerpt = excerpt == null ? "" : excerpt;
        boolean unknown = startOffset == -1 && endOffset == -1;
        boolean positioned = startOffset >= 0 && endOffset >= startOffset;
        if (!unknown && !positioned) {
            throw new IllegalArgumentException(
                    "Text offsets must both be -1 or form a non-negative [start,end) range");
        }
        if (excerpt.isBlank()) throw new IllegalArgumentException("Evidence excerpt is required");
        if (positioned && endOffset - startOffset != excerpt.length()) {
            throw new IllegalArgumentException(
                    "Positioned excerpt length must equal endOffset - startOffset");
        }
    }

    public static EvidenceFragment excerpt(
            SourceDocument document, String section, String excerpt) {
        return excerpt(document, section, excerpt, List.of());
    }

    /** An unpositioned excerpt that keeps the links the source authored inside it. */
    public static EvidenceFragment excerpt(
            SourceDocument document, String section, String excerpt, List<Link> links) {
        return new EvidenceFragment(document, section, -1, -1, excerpt, links);
    }

    /** Evidence whose exact location in the versioned document is known. */
    public static EvidenceFragment positioned(
            SourceDocument document, String section, int startOffset, String excerpt) {
        String text = excerpt == null ? "" : excerpt;
        return new EvidenceFragment(document, section, startOffset,
                startOffset + text.length(), text, List.of());
    }
}
