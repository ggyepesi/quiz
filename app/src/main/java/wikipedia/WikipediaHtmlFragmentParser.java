package wikipedia;

import datasource.evidence.ArticleIntroduction;
import datasource.evidence.WikipediaArticle;
import datasource.evidence.WikipediaFragment;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** One interpretation of Wikipedia HTML fragments for articles and infobox values. */
public final class WikipediaHtmlFragmentParser {
    private static final String BASE = "https://en.wikipedia.org/";

    private WikipediaHtmlFragmentParser() {}

    public static Parsed parse(String html) {
        Element body = Jsoup.parseBodyFragment(html == null ? "" : html, BASE).body();
        return parseChildren(body);
    }

    /** Reads an element's value, excluding the element's own block boundary. */
    public static Parsed parseChildren(Element element) {
        StringBuilder text = new StringBuilder();
        List<ArticleIntroduction.Link> links = new ArrayList<>();
        List<WikipediaFragment.Media> media = new ArrayList<>();
        Map<Element, Span> spans = new IdentityHashMap<>();
        if (element != null) {
            for (Node child : element.childNodes()) append(child, text, links, media, spans);
        }
        trimEnd(text);
        List<WikipediaArticle.ArticleLink> renderedLinks = links.stream()
                .map(link -> new WikipediaArticle.ArticleLink(link.text(), link.targetTitle(),
                        link.start(), link.end()))
                .toList();
        return new Parsed(new WikipediaFragment(text.toString(), renderedLinks, media),
                links, spans);
    }

    private static void append(Node node, StringBuilder text,
            List<ArticleIntroduction.Link> links, List<WikipediaFragment.Media> media,
            Map<Element, Span> spans) {
        if (node instanceof TextNode value) {
            appendText(text, value.getWholeText());
            return;
        }
        if (!(node instanceof Element element)) return;
        String tag = element.normalName();
        boolean block = switch (tag) {
            case "p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
                 "table", "tr", "caption", "dl", "dt", "dd", "figure", "figcaption" -> true;
            default -> false;
        };
        if (block) appendBoundary(text);
        if ("br".equals(tag)) {
            appendBoundary(text);
            return;
        }
        if ("img".equals(tag)) {
            WikipediaFragment.Media image = image(element);
            if (image != null && media.stream().noneMatch(image::equals)) media.add(image);
        }
        if (("td".equals(tag) || "th".equals(tag)) && !text.isEmpty()) {
            char last = text.charAt(text.length() - 1);
            if (last != '\n' && last != ' ') text.append(' ');
        }
        int start = text.length();
        for (Node child : element.childNodes()) append(child, text, links, media, spans);
        int end = text.length();
        spans.put(element, new Span(start, end));
        if ("a".equals(tag)) {
            while (start < end && Character.isWhitespace(text.charAt(start))) start++;
            while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            String target = linkTarget(element);
            if (end > start && !target.isBlank()) {
                links.add(new ArticleIntroduction.Link(
                        text.substring(start, end), target, start, end));
            }
        }
        if (block) appendBoundary(text);
    }

    private static WikipediaFragment.Media image(Element image) {
        String source = image.attr("src").trim();
        if (source.isBlank()) source = image.attr("data-src").trim();
        if (source.isBlank()) return null;
        String url;
        try {
            if (source.startsWith("//")) url = "https:" + source;
            else if (source.startsWith("http://") || source.startsWith("https://")) url = source;
            else url = URI.create(BASE).resolve(source).toString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        String label = image.attr("alt").trim();
        if (label.isBlank()) label = image.attr("title").trim();
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return new WikipediaFragment.Media(label, url,
                lower.endsWith(".svg") || lower.contains(".svg?"));
    }

    private static void appendText(StringBuilder out, String value) {
        if (value == null || value.isEmpty()) return;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n'
                        && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            out.append(character);
        }
    }

    private static void appendBoundary(StringBuilder text) {
        while (!text.isEmpty() && text.charAt(text.length() - 1) == ' ') {
            text.setLength(text.length() - 1);
        }
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n');
    }

    private static void trimEnd(StringBuilder text) {
        while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
        }
    }

    private static String linkTarget(Element link) {
        String title = link.attr("title").trim();
        if (!title.isBlank()) return title;
        String href = link.attr("href").trim();
        int wiki = href.indexOf("/wiki/");
        if (wiki < 0) return "";
        String encoded = href.substring(wiki + "/wiki/".length());
        int fragment = encoded.indexOf('#');
        if (fragment >= 0) encoded = encoded.substring(0, fragment);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8).replace('_', ' ').trim();
    }

    public record Span(int start, int end) { }

    public record Parsed(WikipediaFragment value, List<ArticleIntroduction.Link> links,
                         Map<Element, Span> spans) {
        public Parsed {
            value = value == null ? WikipediaFragment.text("") : value;
            links = links == null ? List.of() : List.copyOf(links);
            spans = spans == null ? Map.of() : spans;
        }
    }
}
