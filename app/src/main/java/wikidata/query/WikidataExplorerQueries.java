package wikidata.query;

public final class WikidataExplorerQueries {

    private WikidataExplorerQueries() {}

    public static String outgoingTriples(
            String rootQid,
            int limit,
            boolean requireEnglishLabel,
            Double minLengthKm,
            Double minAreaKm2) {

        WikidataQueryBuilder q = new WikidataQueryBuilder()
                .selectDistinct(
                        "?property",
                        "?propertyLabel",
                        "?value",
                        "?valueLabel")
                .bindEntity("root", cleanQid(rootQid))
                .rawWhere("?root ?p ?value .")
                .rawWhere("?property wikibase:directClaim ?p .")
                .label("property", "value")
                .orderBy("propertyLabel", "valueLabel")
                .limit(limit);

        addFilters(q, requireEnglishLabel, minLengthKm, minAreaKm2);

        return q.build();
    }

    public static String incomingTriples(
            String rootQid,
            int limit,
            boolean requireEnglishLabel,
            Double minLengthKm,
            Double minAreaKm2) {

        WikidataQueryBuilder q = new WikidataQueryBuilder()
                .selectDistinct(
                        "?property",
                        "?propertyLabel",
                        "?value",
                        "?valueLabel")
                .bindEntity("root", cleanQid(rootQid))
                .rawWhere("?value ?p ?root .")
                .rawWhere("?property wikibase:directClaim ?p .")
                .label("property", "value")
                .orderBy("propertyLabel", "valueLabel")
                .limit(limit);

        addFilters(q, requireEnglishLabel, minLengthKm, minAreaKm2);

        return q.build();
    }

    public static String instanceOfTypes(String rootQid) {
        return new WikidataQueryBuilder()
                .selectDistinct(
                        "?property",
                        "?propertyLabel",
                        "?value",
                        "?valueLabel")
                .bindEntity("root", cleanQid(rootQid))
                .truthy("root", "P31", "value")
                .rawWhere("BIND(wd:P31 AS ?property)")
                .label("property", "value")
                .orderBy("valueLabel")
                .build();
    }
    public static String incomingPropertySummary(String rootQid, int limit) {
        rootQid = cleanQid(rootQid);

        return """
            SELECT ?property ?propertyLabel ?p ?count WHERE {
              BIND(wd:%s AS ?root)

              {
                SELECT ?p (COUNT(*) AS ?count) WHERE {
                  ?value ?p ?root .
                }
                GROUP BY ?p
                ORDER BY DESC(?count)
                LIMIT %d
              }

              ?property wikibase:directClaim ?p .

            %s}
            ORDER BY DESC(?count)
            """.formatted(rootQid, limit, LabelService.service());
    }

    public static String incomingValuesForProperty(
            String rootQid,
            String pid,
            int limit,
            String excludeFilter,
            boolean requireLabel,
            boolean includeMedia) {

        rootQid = cleanQid(rootQid);
        pid = cleanPid(pid);

        String labelPart = requireLabel
                ? "  ?value rdfs:label ?valueLabel .\n  "
                        + LabelService.labelFilter("valueLabel", null) + "\n"
                : LabelService.service();

        String mediaPart = includeMedia
                ? "OPTIONAL { ?value wdt:P18 ?image . }\n"
                : "";

        String select = includeMedia
                ? "SELECT DISTINCT ?value ?valueLabel ?image WHERE"
                : "SELECT DISTINCT ?value ?valueLabel WHERE";

        return """
        %s {
          BIND(wd:%s AS ?root)

          ?value wdt:%s ?root .

          %s

          %s
          %s
        }
        ORDER BY ?valueLabel
        LIMIT %d
        """.formatted(
                select,
                rootQid,
                pid,
                excludeFilter,
                labelPart,
                mediaPart,
                limit);
    }

    public static String allPropertiesForCache() {
        return new WikidataQueryBuilder()
                .select("property", "propertyLabel", "propertyDescription", "datatype")
                .triple("property", "a", "wikibase:Property")
                .triple("property", "wikibase:propertyType", "typeUri")
                .bind("STRAFTER(STR(?typeUri), \"#\")", "datatype")
                .exists("isSingle",
                        "?property p:P2302 ?cs1 . ?cs1 ps:P2302 wd:Q19474404 .")
                .exists("isMulti",
                        "?property p:P2302 ?cs2 . ?cs2 ps:P2302 wd:Q21510857 .")
                .label("property")
                .orderBy("propertyLabel")
                .build();
    }

    /**
     * Source-declared relationships between properties. Kept separate from the large
     * catalogue query so multi-valued metadata cannot duplicate its property rows.
     */
    public static String propertyStructureMetadataForCache() {
        return """
                SELECT DISTINCT ?property ?superproperty ?inverse WHERE {
                  ?property a wikibase:Property .
                  OPTIONAL { ?property wdt:P1647 ?superproperty . }
                  OPTIONAL { ?property wdt:P1696 ?inverse . }
                  FILTER(BOUND(?superproperty) || BOUND(?inverse))
                }
                ORDER BY ?property
                """;
    }

    private static String cleanPid(String pid) {
        if (pid == null) {
            return "";
        }

        pid = pid.trim();

        if (pid.startsWith("wdt:")) {
            pid = pid.substring(4);
        }

        return pid;
    }

    private static void addFilters(
            WikidataQueryBuilder q,
            boolean requireEnglishLabel,
            Double minLengthKm,
            Double minAreaKm2) {

        if (requireEnglishLabel) {
            q.rawWhere("""
FILTER(
    !isIRI(?value)
    ||
    EXISTS {
      ?value rdfs:label ?_label .
      %s
    }
)
""".formatted(LabelService.labelFilter("_label", null)));
        }
        if (minLengthKm != null) {
            q.rawWhere("?value wdt:P2043 ?length .");
            q.rawWhere("FILTER(?length >= " + minLengthKm + ")");
        }

        if (minAreaKm2 != null) {
            q.rawWhere("?value wdt:P2046 ?area .");
            q.rawWhere("FILTER(?area >= " + minAreaKm2 + ")");
        }
    }

    private static String cleanQid(String qid) {
        qid = qid == null ? "" : qid.trim();

        return qid.startsWith("wd:")
                ? qid.substring(3)
                : qid;
    }
}
