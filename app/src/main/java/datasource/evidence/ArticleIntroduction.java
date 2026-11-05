package datasource.evidence;

import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;

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

    /** The first non-blank paragraph in the retained lead, without interpreting it. */
    public String leadParagraph() {
        for (String paragraph : text.split("\\R+")) {
            String value = paragraph.trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    /** The first sentence of {@link #leadParagraph()}, using ordinary English sentence
     * boundaries rather than a punctuation-specific Wikipedia heuristic. */
    public String leadSentence() {
        String paragraph = leadParagraph();
        if (paragraph.isBlank()) return "";
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        sentences.setText(paragraph);
        int start = sentences.first();
        int end = sentences.next();
        return end == BreakIterator.DONE ? paragraph : paragraph.substring(start, end).trim();
    }
}
