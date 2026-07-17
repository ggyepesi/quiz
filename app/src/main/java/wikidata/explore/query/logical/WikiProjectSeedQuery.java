package wikidata.explore.query.logical;

import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.wikiproject.WikiProjectArticle;
import wikidata.explore.wikiproject.WikiProjectCategoryReader;
import wikidata.explore.wikiproject.WikiProjectMediaWikiClient;
import wikidata.explore.wikiproject.WikiProjectQidResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads curated seed pages from Wikipedia/WikiProject assessment
 * categories and resolves them to Wikidata QIDs.
 */
public class WikiProjectSeedQuery
        implements Query<List<WikiProjectArticle>> {

    private final String project;
    private final String importance;
    private final List<String> classes;
    private final int limit;

    public WikiProjectSeedQuery(
            String project,
            String importance,
            List<String> classes,
            int limit) {

        this.project = project == null ? "" : project.trim();
        this.importance = importance == null ? "" : importance.trim();
        this.classes = classes == null ? List.of() : List.copyOf(classes);
        this.limit = Math.max(1, limit);
    }

    @Override
    public String purpose() {
        return "Load WikiProject seeds";
    }

    @Override
    public String skeleton() {
        return "assessment categories -> article pages -> resolve Wikidata QIDs";
    }

    @Override
    public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("project", project);
        p.put("importance", importance);
        p.put("classes", String.join(" ", classes));
        p.put("limit", String.valueOf(limit));
        return p;
    }

    @Override
    public List<WikiProjectArticle> execute(QueryContext context)
            throws Exception {

        WikiProjectMediaWikiClient mw = new WikiProjectMediaWikiClient();
        WikiProjectCategoryReader reader = new WikiProjectCategoryReader(mw);
        List<WikiProjectArticle> out = new ArrayList<>();

        for (String cls : classes) {
            if (out.size() >= limit) {
                break;
            }

            // Combined intersection categories ("…articles of X-importance")
            // exist only for some projects (e.g. Astronomy), not others (e.g.
            // Mythology). "Any"/blank importance uses the class-only category,
            // which every project has.
            boolean anyImportance =
                    importance.isBlank() || importance.equalsIgnoreCase("Any");
            String category = anyImportance
                    ? "Category:" + cls + "-Class " + project + " articles"
                    : "Category:" + cls + "-Class " + project
                            + " articles of " + importance + "-importance";

            int remaining = Math.max(1, limit - out.size());
            final String cat = category;
            // Log the category read as its own step carrying the runnable
            // MediaWiki API URL (queryType "API" → "Open request" link), just
            // like a SPARQL step carries its query.
            List<WikiProjectArticle> categoryArticles = context.step(
                    "WikiProject category: " + cat,
                    "API",
                    "categorymembers -> Talk pages",
                    Map.of("category", cat, "limit", String.valueOf(remaining)),
                    step -> {
                        step.request(reader.firstRequestUrl(cat, remaining));
                        List<WikiProjectArticle> got =
                                reader.categoryMembers(cat, remaining);
                        step.summary(got.size() + " pages");
                        return got;
                    });

            for (WikiProjectArticle article : categoryArticles) {
                if (article.title() == null || article.title().isBlank()) {
                    continue;
                }
                out.add(article);
                if (out.size() >= limit) {
                    break;
                }
            }
        }

        List<WikiProjectArticle> articles = dedupeByTitle(out);

        context.message("WikiProject: resolving Wikidata QIDs for "
                                + articles.size()
                                + " pages...\n");

        if (!articles.isEmpty()) {
            new WikiProjectQidResolver(context.sparql())
                    .attachQids(articles);
        }

        return articles;
    }

    @Override
    public int rowCount(List<WikiProjectArticle> result) {
        return result == null ? 0 : result.size();
    }

    @Override
    public String summary(List<WikiProjectArticle> result) {
        return rowCount(result) + " seeds";
    }

    private static List<WikiProjectArticle> dedupeByTitle(
            List<WikiProjectArticle> in) {

        List<WikiProjectArticle> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (WikiProjectArticle article : in) {
            String key = article.title().toLowerCase();
            if (seen.add(key)) {
                out.add(article);
            }
        }

        return out;
    }
}
