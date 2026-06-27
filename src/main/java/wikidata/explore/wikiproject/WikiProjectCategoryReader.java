package wikidata.explore.wikiproject;

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

    /** The (first-page) MediaWiki API URL this reader would hit for a category
     *  — for logging the request as a runnable link. */
    public String firstRequestUrl(String category, int limit) {
        return firstRequestUrl(category, limit, 1);
    }

    public String firstRequestUrl(String category, int limit, int namespace) {
        return WikiProjectMediaWikiClient.url(
                buildQueryString(category, limit, null, namespace));
    }

    private static String buildQueryString(
            String category, int remaining, String cmcontinue, int namespace) {
        StringBuilder qs = new StringBuilder();
        qs.append("action=query");
        qs.append("&format=json");
        qs.append("&list=categorymembers");
        qs.append("&cmnamespace=").append(namespace);
        qs.append("&cmtype=page");
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
