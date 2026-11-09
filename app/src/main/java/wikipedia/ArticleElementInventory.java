package wikipedia;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which block-level elements one article's HTML contains, and what the article structure
 * does with each: the inventory Wikipedia Discovery compares across articles.
 *
 * <p>An element is named by its tag and its own class attribute as the page states them
 * ({@code table.infobox.vcard}, {@code div.hatnote}), never by a meaning this code assigns.
 * "Block-level" is the HTML notion, not a Wikipedia vocabulary. What the structure did
 * with it is the outcome the section builder recorded for that element while building the
 * retained sections, so the inventory shows what was dropped rather than re-deciding it.</p>
 */
public final class ArticleElementInventory {
    /** HTML block-level elements; inline content is part of the block around it. */
    private static final Set<String> BLOCK_LEVEL = Set.of(
            "address", "article", "aside", "blockquote", "dd", "details", "dialog", "div",
            "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
            "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav",
            "ol", "p", "pre", "section", "table", "ul");
    private static final int EXAMPLE_CHARACTERS = 240;

    private ArticleElementInventory() { }

    /**
     * One element kind in one article: how many there are, what the structure does with
     * them, and the text of the first non-empty one.
     */
    public record ElementKind(String element, String retainedAs, int occurrences,
                              String example) {
        public ElementKind {
            element = element == null ? "" : element;
            retainedAs = retainedAs == null ? "" : retainedAs;
            example = example == null ? "" : example;
        }
    }

    static List<ElementKind> of(Element body, Map<Element, String> outcomes) {
        if (body == null) return List.of();
        Map<String, Tally> kinds = new LinkedHashMap<>();
        for (Element element : body.getAllElements()) {
            if (element == body || !BLOCK_LEVEL.contains(element.normalName())) continue;
            String name = name(element);
            String retainedAs = outcomes == null ? WikipediaArticleClient.NOT_RETAINED
                    : outcomes.getOrDefault(element, WikipediaArticleClient.NOT_RETAINED);
            kinds.computeIfAbsent(name + '\0' + retainedAs,
                    ignored -> new Tally(name, retainedAs)).add(element);
        }
        List<ElementKind> result = new ArrayList<>();
        kinds.values().forEach(tally -> result.add(tally.kind()));
        return List.copyOf(result);
    }

    /** The tag plus the element's own classes, sorted so one kind has one name. */
    static String name(Element element) {
        List<String> classes = new ArrayList<>(element.classNames());
        classes.removeIf(String::isBlank);
        classes.sort(String::compareTo);
        return classes.isEmpty()
                ? element.normalName() : element.normalName() + '.' + String.join(".", classes);
    }

    private static final class Tally {
        private final String element;
        private final String retainedAs;
        private int occurrences;
        private String example = "";

        private Tally(String element, String retainedAs) {
            this.element = element;
            this.retainedAs = retainedAs;
        }

        private void add(Element value) {
            occurrences++;
            if (example.isBlank()) {
                String text = value.text().trim();
                example = text.length() <= EXAMPLE_CHARACTERS
                        ? text : text.substring(0, EXAMPLE_CHARACTERS - 1) + "…";
            }
        }

        private ElementKind kind() {
            return new ElementKind(element, retainedAs, occurrences, example);
        }
    }
}
