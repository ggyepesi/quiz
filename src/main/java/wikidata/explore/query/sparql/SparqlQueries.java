package wikidata.explore.query.sparql;

import wikidata.query.WikidataExplorerQueries;
import wikidata.query.WikidataQueryBuilder;
import wikidata.query.WikidataRootQuery;

import java.util.Collection;

public final class SparqlQueries {

    private SparqlQueries() {}

    public static String sampleClassInstances(
            String classQid,
            String lang,
            int limit) {

        lang = lang == null || lang.isBlank() ? "en" : lang;

        return new WikidataQueryBuilder()
                .selectDistinct("?value", "?valueLabel")
                .bindEntity("root", WikidataQueryBuilder.cleanQid(classQid))
                .truthy("value", "P31", "root")
                .rdfsLabelPattern("value", lang, true)
                .orderBy("valueLabel")
                .limit(limit)
                .build();
    }

    public static String discoverOutgoingProperties(
            Collection<String> qids,
            int limit) {

        int n = Math.max(1, limit);

        return new WikidataQueryBuilder()
                .distinct(false)
                .select(
                        "prop",
                        "propLabel",
                        "type",
                        "count",
                        "example")
                .rawWhere("""
                    {
                      SELECT ?propUri
                             (COUNT(DISTINCT ?item) AS ?count)
                             (SAMPLE(?example) AS ?example)
                      WHERE {
                    """)
                .valuesQids("item", qids)
                .rawWhere("""
                        ?item ?propUri ?example .
                        FILTER(STRSTARTS(
                          STR(?propUri),
                          "http://www.wikidata.org/prop/direct/"
                        ))
                      }
                      GROUP BY ?propUri
                      ORDER BY DESC(?count)
                      LIMIT %d
                    }
                    """.formatted(n))
                .rawWhere("?prop wikibase:directClaim ?propUri .")
                .optional("?prop wikibase:propertyType ?type .")
                .label("prop")
                .orderByRaw("DESC(?count)")
                .limit(n)
                .build();
    }
    public static String discoverIncomingPropertiesToClassQid(
            String classQid,
            int limit) {

        int n = Math.max(1, limit);
        classQid = WikidataQueryBuilder.cleanQid(classQid);

        return new WikidataQueryBuilder()
                .distinct(false)
                .select(
                        "prop",
                        "propLabel",
                        "type",
                        "count")
                .rawWhere("""
                    {
                      SELECT ?propUri
                             (COUNT(DISTINCT ?subject) AS ?count)
                      WHERE {
                        ?subject ?propUri wd:%s .
                        FILTER(STRSTARTS(
                          STR(?subject),
                          "http://www.wikidata.org/entity/Q"
                        ))
                      }
                      GROUP BY ?propUri
                      ORDER BY DESC(?count)
                      LIMIT 40
                    }
                    """.formatted(classQid))
                .rawWhere("?prop wikibase:directClaim ?propUri .")
                .optional("?prop wikibase:propertyType ?type .")
                .label("prop")
                .orderByRaw("DESC(?count)")
                .limit(n)
                .build();
    }

    public static String fieldValueSample(
            String parentVar,
            Collection<String> parentQids,
            String pid,
            String valueVar,
            String lang,
            boolean requireLabel,
            int limit) {

        lang = lang == null || lang.isBlank() ? "en" : lang;
        pid = WikidataQueryBuilder.cleanPid(pid);

        WikidataQueryBuilder q =
                new WikidataQueryBuilder()
                        .selectDistinct(
                                "parent",
                                "parentLabel",
                                valueVar,
                                valueVar + "Label")
                        .valuesQids("parent", parentQids)
                        .truthy("parent", pid, valueVar)
                        .rdfsLabelPattern("parent", lang, true)
                        .rdfsLabelPattern(valueVar, lang, requireLabel)
                        .orderBy("parentLabel", valueVar + "Label")
                        .limit(limit);

        return q.build();
    }

    public static String outgoingTriples(
            String rootQid,
            int limit,
            boolean requireEnglishLabel,
            Double minLengthKm,
            Double minAreaKm2) {

        return WikidataExplorerQueries.outgoingTriples(
                rootQid,
                limit,
                requireEnglishLabel,
                minLengthKm,
                minAreaKm2);
    }

    public static String incomingTriples(
            String rootQid,
            int limit,
            boolean requireEnglishLabel,
            Double minLengthKm,
            Double minAreaKm2) {

        return WikidataExplorerQueries.incomingTriples(
                rootQid,
                limit,
                requireEnglishLabel,
                minLengthKm,
                minAreaKm2);
    }

    public static String instanceOfTypes(String rootQid) {
        return WikidataExplorerQueries.instanceOfTypes(rootQid);
    }

    public static String incomingPropertySummary(
            String rootQid,
            int limit) {

        return WikidataExplorerQueries.incomingPropertySummary(
                rootQid,
                limit);
    }

    public static String incomingValuesForProperty(
            String rootQid,
            String pid,
            int limit,
            String excludeFilter,
            boolean requireLabel,
            boolean includeMedia) {

        return WikidataExplorerQueries.incomingValuesForProperty(
                rootQid,
                pid,
                limit,
                excludeFilter,
                requireLabel,
                includeMedia);
    }

    public static String allPropertiesForCache() {
        return WikidataExplorerQueries.allPropertiesForCache();
    }

    public static WikidataRootQuery rootQuery(
            String rootVar,
            String sparql) {

        return new WikidataRootQuery(rootVar, sparql);
    }
}