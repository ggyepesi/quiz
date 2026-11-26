package domain.datasource.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Divides a retained page into ordered passages at the divisions its author made.
 *
 * <p>Parsing only: a passage is a stretch of source text with its label and position. It
 * says nothing about what the passage is about or who appears in it; recipes that turn
 * passages into instances, and the matching that proposes their participants, are separate
 * constructs. The page's own structure decides the boundaries, never the text's content.</p>
 */
public final class TextPassages {
    private TextPassages() { }

    /** Where a page's author divided it. */
    public enum Division {
        /** Each heading's own content. */
        SECTIONS("Section headings"),
        /** Paragraphs that open with a Roman numeral and a full stop ("XV. Then said…"),
         *  each running until the next numbered paragraph; text before the first is not
         *  a chapter. Brodeur's Prose Edda is divided this way. */
        NUMBERED_CHAPTERS("Numbered chapters");

        private final String label;

        Division(String label) { this.label = label; }

        public String label() { return label; }

        public static Division parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    /** One passage: its label (heading path or chapter numeral), position and text. */
    public record Passage(String label, int start, String text) {
        public Passage {
            label = label == null ? "" : label.trim();
            text = text == null ? "" : text.trim();
        }
    }

    private static final Pattern CHAPTER = Pattern.compile("^([IVXLCDM]+)\\.\\s");

    public static List<Passage> divide(WikipediaArticle page, Division division) {
        if (page == null || division == null) return List.of();
        List<Located> blocks = new ArrayList<>();
        for (WikipediaArticle.Section section : page.sections()) collect(section, "", blocks);
        return switch (division) {
            case SECTIONS -> sections(blocks);
            case NUMBERED_CHAPTERS -> chapters(blocks);
        };
    }

    private static List<Passage> chapters(List<Located> blocks) {
        List<Passage> passages = new ArrayList<>();
        String label = null;
        int start = 0;
        StringBuilder text = new StringBuilder();
        for (Located located : blocks) {
            Matcher numeral = CHAPTER.matcher(located.block().value());
            if (numeral.find()) {
                if (label != null) passages.add(new Passage(label, start, text.toString()));
                label = numeral.group(1);
                start = located.block().start();
                text.setLength(0);
            }
            if (label == null) continue;
            if (!text.isEmpty()) text.append("\n\n");
            text.append(located.block().value().trim());
        }
        if (label != null) passages.add(new Passage(label, start, text.toString()));
        return List.copyOf(passages);
    }

    private static List<Passage> sections(List<Located> blocks) {
        List<Passage> passages = new ArrayList<>();
        String label = null;
        int start = 0;
        StringBuilder text = new StringBuilder();
        for (Located located : blocks) {
            if (!located.path().equals(label)) {
                if (label != null && !text.isEmpty()) {
                    passages.add(new Passage(label, start, text.toString()));
                }
                label = located.path();
                start = located.block().start();
                text.setLength(0);
            }
            if (!text.isEmpty()) text.append("\n\n");
            text.append(located.block().value().trim());
        }
        if (label != null && !text.isEmpty()) passages.add(new Passage(label, start, text.toString()));
        return List.copyOf(passages);
    }

    /** Prose blocks in reading order with their heading path. Tables and captions are
     *  structure around the text, not narrative. */
    private static void collect(WikipediaArticle.Section section, String parent, List<Located> out) {
        String path = parent.isBlank() ? section.heading() : parent + " > " + section.heading();
        for (WikipediaArticle.Block block : section.blocks()) {
            if (block.value() == null || block.value().isBlank()) continue;
            switch (block.kind()) {
                case PARAGRAPH, QUOTATION, LIST, DEFINITION -> out.add(new Located(path, block));
                case LIST_ITEM, TABLE, CAPTION -> { }
            }
        }
        for (WikipediaArticle.Section child : section.sections()) collect(child, path, out);
    }

    private record Located(String path, WikipediaArticle.Block block) { }
}
