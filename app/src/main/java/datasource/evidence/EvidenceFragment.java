package datasource.evidence;

/** Exact supporting text in a versioned document. Offsets use [start,end). */
public record EvidenceFragment(
        SourceDocument document,
        String section,
        int startOffset,
        int endOffset,
        String excerpt) {
    public EvidenceFragment {
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
        return new EvidenceFragment(document, section, -1, -1, excerpt);
    }

    /** Evidence whose exact location in the versioned document is known. */
    public static EvidenceFragment positioned(
            SourceDocument document, String section, int startOffset, String excerpt) {
        String text = excerpt == null ? "" : excerpt;
        return new EvidenceFragment(document, section, startOffset,
                startOffset + text.length(), text);
    }
}
