package wikidata.explore.wikiproject;

import wikidata.WikidataSparqlClient;

import java.util.List;

public class WikiProjectAstronomyDemo {
    public static void main(String[] args) throws Exception {
        WikiProjectMediaWikiClient mediaWiki =
                new WikiProjectMediaWikiClient(
                        "QuizProject/1.0 (ggyepesi@gmail.com)",
                        1000);

        mediaWiki.debug(false);

        WikiProjectCategoryReader reader =
                new WikiProjectCategoryReader(mediaWiki);

        reader.debug(false);

        List<WikiProjectArticle> articles =
                reader.topImportanceAstronomyDemo(30);

        WikidataSparqlClient wikidata =
                new WikidataSparqlClient(
                        "QuizProject/1.0 (ggyepesi@gmail.com)",
                        1);

        WikiProjectQidResolver resolver =
                new WikiProjectQidResolver(wikidata);

        resolver.attachQids(articles);

        System.out.println("WikiProject Astronomy top-importance seeds");
        System.out.println("------------------------------------------");

        for (WikiProjectArticle a : articles) {
            System.out.printf(
                    "%-45s %-12s %s%n",
                    a.title(),
                    a.qid() == null ? "" : a.qid(),
                    a.category());
        }

        System.exit(0);
    }
}
