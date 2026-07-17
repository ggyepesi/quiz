package wikidata.explore.wikiproject;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.util.List;

public class WikiProjectQidResolver {
    private final WikidataSparqlClient client;

    public WikiProjectQidResolver(WikidataSparqlClient client) {
        this.client = client;
    }

    public void attachQids(List<WikiProjectArticle> articles)
            throws Exception {

        if (articles == null || articles.isEmpty()) return;

        String sparql = buildSitelinkQuery(articles);

        for (WikidataBinding b : client.query(sparql)) {
            String title = b.value("title");
            String qid = b.qid("item");

            if (title == null || qid == null) continue;

            for (WikiProjectArticle article : articles) {
                if (article.title().equals(title)) {
                    article.qid(qid);
                    break;
                }
            }
        }
    }

    private static String buildSitelinkQuery(
            List<WikiProjectArticle> articles) {

        StringBuilder sb = new StringBuilder();

        sb.append("SELECT ?title ?item WHERE {\n");
        sb.append("  VALUES ?title {\n");

        for (WikiProjectArticle a : articles) {
            sb.append("    \"")
              .append(escape(a.title()))
              .append("\"@en\n");
        }

        sb.append("  }\n\n");
        sb.append("  ?article schema:about ?item ;\n");
        sb.append("           schema:isPartOf <https://en.wikipedia.org/> ;\n");
        sb.append("           schema:name ?title .\n");
        sb.append("}\n");

        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
