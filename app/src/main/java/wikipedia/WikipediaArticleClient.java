package wikipedia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datasource.evidence.ContentDigest;
import datasource.evidence.SourceDocument;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads immutable, versioned English Wikipedia article text. */
public final class WikipediaArticleClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Fetcher fetcher;

    public WikipediaArticleClient() {
        this(uri -> {
            try (java.io.InputStream in = objectview.utils.UrlOpener.open(uri.toURL())) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    public WikipediaArticleClient(Fetcher fetcher) {
        this.fetcher = java.util.Objects.requireNonNull(fetcher);
    }

    public Query<Article> byTitle(String title) {
        String requested = title == null ? "" : title.trim();
        if (requested.isBlank()) throw new IllegalArgumentException("Article title is required");
        URI uri = api(requested);
        return new Query<>() {
            @Override public String purpose() { return "Read versioned Wikipedia article"; }
            @Override public String skeleton() { return "extracts + revision + content digest"; }
            @Override public String queryType() { return "Wikipedia API"; }
            @Override public String description() { return "English Wikipedia article text"; }
            @Override public Map<String, String> parameters() {
                return Map.of("title", requested);
            }
            @Override public Article execute(QueryContext context) throws Exception {
                return context.step("Read versioned article text", "Wikipedia API", skeleton(),
                        parameters(), step -> {
                            step.request(uri.toString());
                            Article article = parse(JSON.readTree(fetcher.fetch(uri)));
            step.summary(article == null ? "Article unavailable"
                                    : article.text().length() + " character(s), "
                                    + article.categories().size() + " categor"
                                    + (article.categories().size() == 1 ? "y" : "ies"));
                            return article;
                        });
            }
            @Override public int rowCount(Article result) { return result == null ? 0 : 1; }
        };
    }

    static Article parse(JsonNode root) {
        JsonNode pages = root.path("query").path("pages");
        JsonNode page = pages.isArray() && !pages.isEmpty() ? pages.get(0) : null;
        if (page == null || page.path("missing").asBoolean(false)) return null;
        String title = page.path("title").asText("");
        String text = page.path("extract").asText("");
        String revision = page.path("lastrevid").asText("");
        if (revision.isBlank() && page.path("revisions").isArray()
                && !page.path("revisions").isEmpty()) {
            revision = page.path("revisions").get(0).path("revid").asText("");
        }
        if (title.isBlank() || revision.isBlank()) return null;
        List<String> categories = new ArrayList<>();
        JsonNode categoryNodes = page.path("categories");
        if (categoryNodes.isArray()) {
            for (JsonNode category : categoryNodes) {
                String value = category.path("title").asText("")
                        .replaceFirst("^Category:", "").trim();
                if (!value.isBlank()) categories.add(value);
            }
        }
        String url = pageUrl(title);
        SourceDocument document = new SourceDocument("Wikipedia (English)", title, title,
                url, revision, new ContentDigest("sha256",
                sha256(text + "\nCategories:\n" + String.join("\n", categories))),
                Instant.now().toString());
        return new Article(document, text, categories);
    }

    private static URI api(String title) {
        return URI.create("https://en.wikipedia.org/w/api.php?action=query"
                + "&prop=extracts%7Crevisions%7Ccategories&cllimit=max&clshow=!hidden"
                + "&explaintext=1&exsectionformat=plain"
                + "&rvprop=ids%7Ctimestamp&redirects=1&format=json&formatversion=2&titles="
                + encode(title));
    }

    private static String pageUrl(String title) {
        return "https://en.wikipedia.org/wiki/" + encode(title.replace(' ', '_'));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record Article(SourceDocument document, String text, List<String> categories) {
        public Article {
            document = java.util.Objects.requireNonNull(document);
            text = text == null ? "" : text;
            categories = categories == null ? List.of() : List.copyOf(categories);
        }
        public String title() { return document.title(); }
        public String url() { return document.url(); }
    }

    @FunctionalInterface
    public interface Fetcher {
        String fetch(URI uri) throws Exception;
    }
}
