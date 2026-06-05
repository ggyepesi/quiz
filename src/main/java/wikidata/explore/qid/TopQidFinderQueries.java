package wikidata.explore.qid;

public class TopQidFinderQueries {

    public static String searchEntities(
            String text,
            int limit) {

        String escaped = sparqlString(text);

        return """
                SELECT DISTINCT ?item ?itemLabel ?itemDescription WHERE {
                  SERVICE wikibase:mwapi {
                    bd:serviceParam wikibase:endpoint "www.wikidata.org" .
                    bd:serviceParam wikibase:api "EntitySearch" .
                    bd:serviceParam mwapi:search "%s" .
                    bd:serviceParam mwapi:language "en" .
                    ?item wikibase:apiOutputItem mwapi:item .
                  }

                  SERVICE wikibase:label {
                    bd:serviceParam wikibase:language "en" .
                    ?item rdfs:label ?itemLabel .
                    ?item schema:description ?itemDescription .
                  }
                }
                LIMIT %d
                """.formatted(escaped, Math.max(1, limit));
    }

    public static String exactLabelOrAlias(
            String text,
            int limit) {

        String escaped = sparqlString(text);

        return """
                SELECT DISTINCT ?item ?itemLabel ?itemDescription WHERE {
                  {
                    ?item rdfs:label "%s"@en .
                  }
                  UNION
                  {
                    ?item skos:altLabel "%s"@en .
                  }

                  SERVICE wikibase:label {
                    bd:serviceParam wikibase:language "en" .
                    ?item rdfs:label ?itemLabel .
                    ?item schema:description ?itemDescription .
                  }
                }
                LIMIT %d
                """.formatted(escaped, escaped, Math.max(1, limit));
    }

    public static String outgoingStatements(
            String qid,
            int limit) {

        qid = cleanQid(qid);

        return """
                SELECT DISTINCT
                  ?property
                  ?propertyLabel
                  ?value
                  ?valueLabel
                WHERE {
                  wd:%s ?p ?value .

                  FILTER(STRSTARTS(
                    STR(?p),
                    "http://www.wikidata.org/prop/direct/"))

                  ?property wikibase:directClaim ?p .

                  OPTIONAL {
                    ?value rdfs:label ?valueLabel .
                    FILTER(LANG(?valueLabel) = "en")
                  }

                  SERVICE wikibase:label {
                    bd:serviceParam wikibase:language "en" .
                    ?property rdfs:label ?propertyLabel .
                  }
                }
                ORDER BY ?propertyLabel ?valueLabel
                LIMIT %d
                """.formatted(qid, Math.max(1, limit));
    }

    private static String cleanQid(String qid) {
        if (qid == null) {
            return "";
        }

        qid = qid.trim();

        if (qid.startsWith("wd:")) {
            qid = qid.substring(3);
        }

        return qid.trim();
    }

    private static String sparqlString(String s) {
        if (s == null) {
            return "";
        }

        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
