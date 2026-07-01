package wikidata.explore.query.logical;

import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;
import wikidata.explore.wikiproject.WikiProjectArticle;
import wikidata.explore.wikiproject.WikiProjectCategoryReader;
import wikidata.explore.wikiproject.WikiProjectMediaWikiClient;
import wikidata.explore.wikiproject.WikiProjectQidResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls the article members of a plain Wikipedia content category (namespace 0,
 * e.g. {@code Category:Labours of Hercules}) and resolves them to Wikidata QIDs
 * — for sets that Wikidata under-models structurally but Wikipedia curates as a
 * category (the labours: 6/12 in Wikidata, all in the enwiki category). The
 * resolved QIDs feed a class's Seed QIDs. (Issue #40.)
 */
public class CategorySeedQuery implements Query<List<WikiProjectArticle>> {

    private final String category;
    private final int limit;

    public CategorySeedQuery(String category, int limit) {
        String c = category == null ? "" : category.trim();
        // Accept "Labours of Hercules" or "Category:Labours of Hercules".
        this.category = c.regionMatches(true, 0, "Category:", 0, 9)
                ? c : "Category:" + c;
        this.limit = Math.max(1, limit);
    }

    @Override public String purpose() { return "Load Wikipedia category"; }

    @Override public String skeleton() {
        return "category members (ns 0) -> resolve Wikidata QIDs";
    }

    @Override public Map<String, String> parameters() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("category", category);
        p.put("limit", String.valueOf(limit));
        return p;
    }

    @Override
    public List<WikiProjectArticle> execute(QueryContext context) throws Exception {
        return context.step(
                category,
                "API",
                "categorymembers (ns 0) -> resolve QIDs",
                Map.of("category", category),
                step -> {
                    WikiProjectCategoryReader reader =
                            new WikiProjectCategoryReader(new WikiProjectMediaWikiClient());
                    step.request(reader.firstRequestUrl(category, limit, 0));

                    List<WikiProjectArticle> articles =
                            reader.categoryMembers(category, limit, 0);

                    if (!articles.isEmpty()) {
                        context.message("Resolving Wikidata QIDs for "
                                + articles.size() + " pages…\n");
                        new WikiProjectQidResolver(context.sparql()).attachQids(articles);
                    }
                    long resolved = articles.stream()
                            .filter(a -> a != null && a.qid() != null
                                    && a.qid().matches("Q\\d+"))
                            .count();
                    step.summary(articles.size() + " pages, " + resolved + " QIDs");
                    return articles;
                });
    }

    @Override public int rowCount(List<WikiProjectArticle> r) {
        return r == null ? 0 : r.size();
    }

    @Override public String summary(List<WikiProjectArticle> r) {
        return rowCount(r) + " pages";
    }
}
