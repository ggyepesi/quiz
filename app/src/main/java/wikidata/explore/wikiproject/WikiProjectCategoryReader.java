package wikidata.explore.wikiproject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads WikiProject assessment categories.
 *
 * Important: assessment categories contain Talk pages, e.g.
 * Talk:Betelgeuse. We query namespace 1 and strip "Talk:" before
 * resolving to Wikidata QIDs.
 */
public class WikiProjectCategoryReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PAGE_PATTERN =
            Pattern.compile("\\{\\s*\"pageid\"\\s*:\\s*(\\d+).*?\"title\"\\s*:\\s*\"(.*?)\"",
                    Pattern.DOTALL);

    private static final Pattern CONTINUE_PATTERN =
            Pattern.compile("\"cmcontinue\"\\s*:\\s*\"(.*?)\"");

    private final WikiProjectMediaWikiClient client;
    private boolean debug;

    public WikiProjectCategoryReader(WikiProjectMediaWikiClient client) {
        this.client = client;
    }

    public void debug(boolean debug) {
        this.debug = debug;
    }

    public List<WikiProjectArticle> categoryMembers(
            String category,
            int limit) throws Exception {
        // ns 1 (Talk) — WikiProject assessment categories hold Talk pages.
        return categoryMembers(category, limit, 1);
    }

    /** Members in a given namespace: ns 1 (Talk) for assessment categories,
     *  ns 0 (article) for a plain content category (e.g. Labours of Hercules). */
    public List<WikiProjectArticle> categoryMembers(
            String category,
            int limit,
            int namespace) throws Exception {

        // A content-category page is itself the entity-bearing Wikipedia page. Ask
        // MediaWiki to use categorymembers as a generator and attach pageprops, so
        // wikibase_item arrives with the title and page id in the SAME response.
        // The former second-stage WDQS sitelink lookup missed redirects, normalized
        // titles and recently changed sitelinks, leaving a blank QID beside a numeric
        // MediaWiki page id. Talk-page assessment categories still need their article
        // title stripped first, so they retain the established resolver path.
        if (namespace == 0 || namespace == 14) {
            return generatedCategoryMembers(category, limit, namespace);
        }

        List<WikiProjectArticle> out = new ArrayList<>();
        String cmcontinue = null;

        while (out.size() < limit) {
            String json = client.get(
                    buildQueryString(category, limit - out.size(), cmcontinue, namespace));

            if (debug) {
                System.out.println("WikiProjectCategoryReader json:");
                System.out.println(json);
            }

            for (WikiProjectArticle a : parseMembers(json, category)) {
                out.add(a);

                if (out.size() >= limit) {
                    break;
                }
            }

            cmcontinue = parseContinue(json);

            if (cmcontinue == null || cmcontinue.isBlank()) {
                break;
            }
        }

        return out;
    }

    private List<WikiProjectArticle> generatedCategoryMembers(
            String category, int limit, int namespace) throws Exception {
        List<WikiProjectArticle> out = new ArrayList<>();
        String continuation = null;
        while (out.size() < limit) {
            String json = client.get(buildGeneratedMemberQueryString(
                    category, limit - out.size(), continuation, namespace));
            if (debug) {
                System.out.println("WikiProjectCategoryReader content json:");
                System.out.println(json);
            }
            for (WikiProjectArticle article : parsePageObjects(json, category)) {
                out.add(article);
                if (out.size() >= limit) break;
            }
            continuation = parseGeneratorContinue(json);
            if (continuation == null || continuation.isBlank()) break;
        }
        // A generator yields its page set in no particular order — the members are
        // the same ones `list=categorymembers` returns, but the sequence is arbitrary.
        // That was invisible while this only fed Seed QIDs; a browser shows the rows
        // to a person. Sorted by title, which is deterministic and readable — not
        // MediaWiki's sortkey order, which a generator does not expose.
        out.sort(java.util.Comparator.comparing(
                article -> article.title() == null ? "" : article.title(),
                String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The (first-page) MediaWiki API URL this reader would hit for a category
     *  — for logging the request as a runnable link. */
    public String firstRequestUrl(String category, int limit) {
        return firstRequestUrl(category, limit, 1);
    }

    public String firstRequestUrl(String category, int limit, int namespace) {
        return WikiProjectMediaWikiClient.url(
                namespace == 0 || namespace == 14
                        ? buildGeneratedMemberQueryString(category, limit, null, namespace)
                        : buildQueryString(category, limit, null, namespace));
    }

    /** Direct child categories, loaded lazily for the category browser. */
    public List<WikiProjectArticle> subcategories(String category, int limit)
            throws Exception {
        return categoryMembers(category, limit, 14);
    }

    public String firstSubcategoryRequestUrl(String category, int limit) {
        return firstRequestUrl(category, limit, 14);
    }

    /** Categories containing this category page — its immediate parents. */
    public List<WikiProjectArticle> parentCategories(String category, int limit)
            throws Exception {
        List<WikiProjectArticle> out = new ArrayList<>();
        String continuation = null;
        while (out.size() < limit) {
            String json = client.get(buildParentQueryString(
                    category, limit - out.size(), continuation));
            for (WikiProjectArticle parent : parsePageObjects(json, category)) {
                if (out.stream().noneMatch(existing ->
                        existing.title().equals(parent.title()))) out.add(parent);
                if (out.size() >= limit) break;
            }
            continuation = JSON.readTree(json == null ? "{}" : json)
                    .path("continue").path("gclcontinue").asText("");
            if (continuation.isBlank()) break;
        }
        sortByTitle(out);
        return List.copyOf(out);
    }

    public String firstParentRequestUrl(String category, int limit) {
        return WikiProjectMediaWikiClient.url(
                buildParentQueryString(category, limit, null));
    }

    private static String buildParentQueryString(
            String category, int remaining, String continuation) {
        String title = category == null ? "" : category.trim();
        if (!title.regionMatches(true, 0, "Category:", 0, 9)) {
            title = "Category:" + title;
        }
        StringBuilder qs = new StringBuilder();
        qs.append("action=query&format=json&formatversion=2");
        qs.append("&titles=").append(WikiProjectMediaWikiClient.enc(title));
        // !hidden drops maintenance categories ("Commons category link is on
        // Wikidata", "Wikipedia categories named after revolutions"). They are not
        // taxonomy, and in a browser they read as parents that lead nowhere.
        qs.append("&generator=categories&gclnamespace=14&gclshow=!hidden");
        qs.append("&gcllimit=").append(Math.clamp(remaining, 1, 500));
        qs.append("&prop=pageprops&ppprop=wikibase_item");
        if (continuation != null && !continuation.isBlank()) {
            qs.append("&gclcontinue=").append(WikiProjectMediaWikiClient.enc(continuation));
        }
        return qs.toString();
    }

    private static String buildGeneratedMemberQueryString(
            String category, int remaining, String continuation, int namespace) {
        StringBuilder qs = new StringBuilder();
        qs.append("action=query");
        qs.append("&format=json&formatversion=2");
        qs.append("&generator=categorymembers");
        qs.append("&gcmnamespace=").append(namespace);
        qs.append("&gcmtype=").append(namespace == 14 ? "subcat" : "page");
        qs.append("&gcmlimit=").append(Math.clamp(remaining, 1, 500));
        qs.append("&gcmtitle=").append(WikiProjectMediaWikiClient.enc(category));
        qs.append("&prop=pageprops&ppprop=wikibase_item");
        if (continuation != null && !continuation.isBlank()) {
            qs.append("&gcmcontinue=").append(
                    WikiProjectMediaWikiClient.enc(continuation));
        }
        return qs.toString();
    }

    private static String buildQueryString(
            String category, int remaining, String cmcontinue, int namespace) {
        StringBuilder qs = new StringBuilder();
        qs.append("action=query");
        qs.append("&format=json");
        qs.append("&list=categorymembers");
        qs.append("&cmnamespace=").append(namespace);
        // MediaWiki distinguishes ordinary members ("page") from category
        // members ("subcat"). Asking for namespace 14 while retaining the
        // page-only filter produces an empty intersection, even when the
        // category has children.
        qs.append("&cmtype=").append(namespace == 14 ? "subcat" : "page");
        qs.append("&cmlimit=").append(Math.clamp(remaining, 1, 500));
        qs.append("&cmprop=").append(WikiProjectMediaWikiClient.enc("ids|title"));
        qs.append("&cmtitle=").append(WikiProjectMediaWikiClient.enc(category));
        if (cmcontinue != null && !cmcontinue.isBlank()) {
            qs.append("&cmcontinue=").append(WikiProjectMediaWikiClient.enc(cmcontinue));
        }
        return qs.toString();
    }

    public List<WikiProjectArticle> topImportanceAstronomyDemo(
            int limit) throws Exception {

        String[] categories = {
                "Category:FA-Class Astronomy articles of Top-importance",
                "Category:FL-Class Astronomy articles of Top-importance",
                "Category:GA-Class Astronomy articles of Top-importance",
                "Category:B-Class Astronomy articles of Top-importance",
                "Category:C-Class Astronomy articles of Top-importance",
                "Category:Start-Class Astronomy articles of Top-importance"
        };

        List<WikiProjectArticle> out = new ArrayList<>();

        for (String category : categories) {
            if (out.size() >= limit) break;
            out.addAll(categoryMembers(category, limit - out.size()));
        }

        return out;
    }

    private static List<WikiProjectArticle> parseMembers(
            String json,
            String category) {

        List<WikiProjectArticle> out = new ArrayList<>();
        Matcher m = PAGE_PATTERN.matcher(json);

        while (m.find()) {
            int pageId = Integer.parseInt(m.group(1));
            String assessmentTitle = unescapeJson(m.group(2));
            String articleTitle =
                    articleTitleFromAssessmentTitle(assessmentTitle);

            if (!articleTitle.isBlank()) {
                out.add(new WikiProjectArticle(
                        articleTitle,
                        assessmentTitle,
                        pageId,
                        category));
            }
        }

        return out;
    }

    private static String parseContinue(String json) {
        Matcher m = CONTINUE_PATTERN.matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    private static List<WikiProjectArticle> parsePageObjects(
            String json, String category) throws Exception {
        List<WikiProjectArticle> out = new ArrayList<>();
        JsonNode pages = JSON.readTree(json == null ? "{}" : json)
                .path("query").path("pages");
        if (!pages.isArray()) return out;
        for (JsonNode page : pages) {
            String title = page.path("title").asText("").trim();
            int pageId = page.path("pageid").asInt(0);
            if (title.isBlank() || pageId <= 0) continue;
            WikiProjectArticle article = new WikiProjectArticle(
                    title, title, pageId, category);
            article.qid(page.path("pageprops").path("wikibase_item").asText(""));
            out.add(article);
        }
        return out;
    }

    private static String parseGeneratorContinue(String json) throws Exception {
        String value = JSON.readTree(json == null ? "{}" : json)
                .path("continue").path("gcmcontinue").asText("");
        return value.isBlank() ? null : value;
    }

    private static void sortByTitle(List<WikiProjectArticle> pages) {
        pages.sort(java.util.Comparator.comparing(
                page -> page.title() == null ? "" : page.title(),
                String.CASE_INSENSITIVE_ORDER));
    }

    public static String articleTitleFromAssessmentTitle(String title) {
        if (title == null) return "";

        title = title.trim();

        if (title.startsWith("Talk:")) {
            return title.substring("Talk:".length()).trim();
        }

        return title;
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
