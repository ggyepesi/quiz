package wikidata.explore.query.template.sparql;

import wikidata.explore.query.core.QueryTemplate;
import wikidata.query.WikidataExplorerQueries;
import wikidata.query.WikidataQueryBuilder;
import wikidata.query.WikidataRootQuery;

import java.util.Collection;
import java.util.Map;

public final class SparqlQueries {

    private SparqlQueries() {}

    /**
     * LIMIT applies inside the subquery, before the outer sort — a flat
     * ORDER BY over a huge class (e.g. Q523 star) sorts millions of rows
     * and times out. The sample is index-order, sorted for display only.
     */
    public static final QueryTemplate SAMPLE_CLASS_INSTANCES =
            new QueryTemplate(
                    "Sample class instances",
                    """
                    SELECT * WHERE {
                      {
                        SELECT DISTINCT ?value ?valueLabel WHERE {
                          BIND(wd:${classQid} AS ?root)
                          ?value wdt:P31 ?root .
                          ?value rdfs:label ?valueLabel .
                          FILTER(LANG(?valueLabel) = "${lang}")
                        }
                        LIMIT ${limit}
                      }
                    }
                    ORDER BY ?valueLabel
                    """);

    /**
     * The wikibase:directClaim join INSIDE the counting subquery is what
     * makes this fast: the engine probes each direct property as an index
     * range count instead of scanning every triple that points at the
     * class item. The unrestricted form ({@code ?subject ?propUri wd:X})
     * wades through millions of statement/reference nodes for popular
     * classes (e.g. Q523 star) and times out. Counts are exact, and a
     * subject filter is unnecessary — wdt: triples only have wd: subjects.
     */
    public static final QueryTemplate DISCOVER_INCOMING_PROPERTIES_TO_CLASS =
            new QueryTemplate(
                    "Discover incoming properties to class QID",
                    """
                    SELECT ?prop ?propLabel ?type ?count
                    WHERE {
                      {
                        SELECT ?propUri (COUNT(*) AS ?count)
                        WHERE {
                          ?propEntity wikibase:directClaim ?propUri .
                          ?subject ?propUri wd:${classQid} .
                        }
                        GROUP BY ?propUri
                        ORDER BY DESC(?count)
                        LIMIT ${innerLimit}
                      }

                      ?prop wikibase:directClaim ?propUri .
                      OPTIONAL { ?prop wikibase:propertyType ?type . }

                      SERVICE wikibase:label {
                        bd:serviceParam wikibase:language "en" .
                      }
                    }
                    ORDER BY DESC(?count)
                    LIMIT ${limit}
                    """);

    public static final QueryTemplate SAMPLE_INSTANCES_BY_P31 =
            new QueryTemplate(
                    "Sample instances by P31",
                    """
                    SELECT ?item WHERE {
                      ?item wdt:P31 wd:${classQid} .
                    }
                    LIMIT ${limit}
                    """);

    public static final QueryTemplate ENTITY_LABEL =
            new QueryTemplate(
                    "Entity label",
                    """
                    SELECT ?label WHERE {
                      wd:${qid} rdfs:label ?label .
                      FILTER(LANG(?label) = "${lang}")
                    }
                    LIMIT 1
                    """);

    /**
     * Direct subclasses (P279) of a membership type, each with its instance
     * count and one example, ordered by count. Powers the "Discover subtypes"
     * helper so a user can see, e.g., that "zodiacal constellation" has 12 while
     * "Chinese constellation" has 130, before choosing what to include.
     *
     * <p>Counts <b>total</b> instances of each subtype (≈ how many it adds;
     * overlap with the base is usually tiny). The earlier query also did
     * {@code FILTER NOT EXISTS} (exact "new") and a per-instance label join +
     * GROUP_CONCAT — both scan every instance and <b>timed out for huge classes
     * (Star Q523, ~3M)</b>. The grouped count alone is cheap (~2s); the label
     * join and NOT EXISTS were the killers. So: count in a bounded inner
     * subquery (no labels), take one {@code SAMPLE} example, and label only the
     * top-N types + their one example via {@code SERVICE} (#47).
     */
    public static String discoverMembershipSubtypes(
            String baseQid,
            int limit) {

        String base = WikidataQueryBuilder.cleanQid(baseQid);
        int n = Math.max(1, limit);

        return """
               SELECT ?type ?typeLabel ?nNew ?examples WHERE {
                 {
                   SELECT ?type (COUNT(?c) AS ?nNew) (SAMPLE(?c) AS ?example) WHERE {
                     ?type wdt:P279 wd:%s .
                     ?c wdt:P31 ?type .
                   }
                   GROUP BY ?type
                   ORDER BY DESC(?nNew)
                   LIMIT %d
                 }
                 SERVICE wikibase:label {
                   bd:serviceParam wikibase:language "en".
                   ?type rdfs:label ?typeLabel .
                   ?example rdfs:label ?examples .
                 }
               }
               ORDER BY DESC(?nNew)
               """.formatted(base, n);
    }

    /**
     * DBpedia infobox properties present on a sample of a class's instances
     * (joined by owl:sameAs to their Wikidata QIDs), each with how many of the
     * sampled instances have it + an example value. Powers the "Discover
     * properties" helper for DBpedia-sourced fields. Runs against the DBPEDIA
     * endpoint.
     */
    public static String discoverDBpediaProperties(Collection<String> qids) {
        StringBuilder values = new StringBuilder();
        for (String q : qids) {
            values.append("<http://www.wikidata.org/entity/")
                  .append(WikidataQueryBuilder.cleanQid(q))
                  .append("> ");
        }
        return """
               PREFIX dbp: <http://dbpedia.org/property/>
               PREFIX owl: <http://www.w3.org/2002/07/owl#>
               SELECT ?p (COUNT(DISTINCT ?dbr) AS ?n) (SAMPLE(?o) AS ?ex)
               WHERE {
                 VALUES ?wd { %s }
                 ?dbr owl:sameAs ?wd .
                 ?dbr ?p ?o .
                 FILTER(STRSTARTS(STR(?p), "http://dbpedia.org/property/"))
               }
               GROUP BY ?p
               ORDER BY DESC(?n)
               """.formatted(values.toString().trim());
    }

    public static String sampleInstancesByP31(
            String classQid,
            int limit) {

        return SAMPLE_INSTANCES_BY_P31.render(Map.of(
                "classQid", WikidataQueryBuilder.cleanQid(classQid),
                "limit", Math.max(1, limit)));
    }

    public static String entityLabel(
            String qid,
            String lang) {

        return ENTITY_LABEL.render(Map.of(
                "qid", WikidataQueryBuilder.cleanQid(qid),
                "lang", lang == null || lang.isBlank() ? "en" : lang));
    }

    public static String sampleClassInstances(
            String classQid,
            String lang,
            int limit) {

        return SAMPLE_CLASS_INSTANCES.render(Map.of(
                "classQid", WikidataQueryBuilder.cleanQid(classQid),
                "lang", lang == null || lang.isBlank() ? "en" : lang,
                "limit", Math.max(1, limit)));
    }

    public static String discoverOutgoingProperties(
            Collection<String> qids,
            int limit) {

        int n = Math.max(1, limit);

        return new WikidataQueryBuilder()
                .distinct(false)
                .select("prop", "propLabel", "type", "count", "example")
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

        return DISCOVER_INCOMING_PROPERTIES_TO_CLASS.render(Map.of(
                "classQid", WikidataQueryBuilder.cleanQid(classQid),
                "innerLimit", 40,
                "limit", n));
    }

    /**
     * Properties pointing AT a sample of the class's instances — the mirror of
     * {@link #discoverOutgoingProperties}. (The class-QID variant above wrongly
     * looked for properties pointing at the class item itself, so an incoming
     * edge like a star's P59 "constellation" was never found.)
     */
    public static String discoverIncomingProperties(
            Collection<String> qids,
            int limit) {

        int n = Math.max(1, limit);

        return new WikidataQueryBuilder()
                .distinct(false)
                .select("prop", "propLabel", "type", "count", "example")
                .rawWhere("""
                        {
                          SELECT ?propUri
                                 (COUNT(DISTINCT ?subject) AS ?count)
                                 (SAMPLE(?subject) AS ?example)
                          WHERE {
                        """)
                .valuesQids("item", qids)
                .rawWhere("""
                            ?subject ?propUri ?item .
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

        return WikidataExplorerQueries.incomingPropertySummary(rootQid, limit);
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