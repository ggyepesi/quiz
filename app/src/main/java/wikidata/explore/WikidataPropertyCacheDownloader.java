package wikidata.explore;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.query.WikidataExplorerQueries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

            Map<String, Set<String>> superproperties = new LinkedHashMap<>();
            Map<String, Set<String>> inverses = new LinkedHashMap<>();
            for (WikidataBinding b : client.query(
                    WikidataExplorerQueries.propertyStructureMetadataForCache())) {
                String pid = b.qid("property");
                if (!WikidataIds.isPid(pid)) continue;
                add(superproperties, pid, b.qid("superproperty"));
                add(inverses, pid, b.qid("inverse"));
            }

            for (WikidataBinding b : client.query(sparql)) {
                String pid = b.qid("property");
                String label = b.label("property");
                String description = b.value("propertyDescription");
                String datatype = b.value("datatype");
                String isSingle = b.value("isSingle");
                String isMulti  = b.value("isMulti");

                String cardinality = "true".equals(isSingle) ? "SINGLE"
                        : "true".equals(isMulti)             ? "COLLECTION"
                        :                                      "AUTO";

                if (pid != null && WikidataIds.isPid(pid)) {
                    properties.add(new WikidataProperty(
                            pid,
                            label == null || label.isBlank()
                                    ? pid
                                    : label,
                            description == null
                                    ? ""
                                    : description,
                            datatype == null
                                    ? ""
                                    : datatype,
                            cardinality,
                            joined(superproperties.get(pid)),
                            joined(inverses.get(pid))));
                }
            }

            new WikidataPropertyStore().write(properties);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void add(Map<String, Set<String>> values, String pid, String related) {
        if (WikidataIds.isPid(related)) {
            values.computeIfAbsent(pid, ignored -> new LinkedHashSet<>()).add(related);
        }
    }

    private static String joined(Set<String> values) {
        return values == null ? "" : String.join(",", values);
    }
}
