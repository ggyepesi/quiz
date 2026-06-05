package wikidata.explore;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.query.WikidataExplorerQueries;

import java.util.ArrayList;
import java.util.List;

public class WikidataPropertyCacheDownloader {
    public static void main(String[] args) {
        try (WikidataSparqlClient client =
                     new WikidataSparqlClient(
                             "QuizProject/1.0 (ggyepesi@gmail.com)",
                             1)) {

            String sparql =
                    WikidataExplorerQueries.allPropertiesForCache();

            List<WikidataProperty> properties =
                    new ArrayList<>();

            for (WikidataBinding b : client.query(sparql)) {
                String pid = b.qid("property");
                String label = b.label("property");
                String description = b.value("propertyDescription");

                if (pid != null && pid.matches("P\\d+")) {
                    properties.add(new WikidataProperty(
                            pid,
                            label == null || label.isBlank()
                                    ? pid
                                    : label,
                            description == null
                                    ? ""
                                    : description));
                }
            }

            new WikidataPropertyStore().write(properties);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}