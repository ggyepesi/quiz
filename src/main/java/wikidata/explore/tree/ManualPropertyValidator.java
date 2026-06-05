package wikidata.explore.tree;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

public class ManualPropertyValidator {
    private final WikidataSparqlClient client;

    public ManualPropertyValidator(WikidataSparqlClient client) {
        this.client = client;
    }

    public PropertyValidationResult validate(String pid) throws Exception {
        pid = cleanPid(pid);
        if (!pid.matches("P\\d+")) {
            return new PropertyValidationResult(false, pid, "", "", "", "", "Invalid PID format");
        }

        for (WikidataBinding b : client.query(propertyMetadataQuery(pid))) {
            String label = b.value("propertyLabel");
            String description = b.value("propertyDescription");
            String type = b.value("type");
            return new PropertyValidationResult(
                    true,
                    pid,
                    label,
                    description,
                    type,
                    recommendedFieldType(type),
                    "Valid property");
        }

        return new PropertyValidationResult(false, pid, "", "", "", "", "Property not found");
    }

    public static String propertyMetadataQuery(String pid) {
        pid = cleanPid(pid);
        return """
                SELECT ?property ?propertyLabel ?propertyDescription ?type WHERE {
                  BIND(wd:%s AS ?property)
                  ?property wikibase:propertyType ?type .
                  SERVICE wikibase:label {
                    bd:serviceParam wikibase:language "en" .
                    ?property rdfs:label ?propertyLabel .
                    ?property schema:description ?propertyDescription .
                  }
                }
                LIMIT 1
                """.formatted(pid);
    }

    private static String recommendedFieldType(String type) {
        if (type == null) return "AUTO";
        if (type.endsWith("CommonsMedia")) return "IMAGE";
        if (type.endsWith("WikibaseItem") || type.endsWith("WikibaseProperty")) return "ENTITY";
        if (type.endsWith("Quantity")) return "NUMBER";
        if (type.endsWith("Time")) return "DATE";
        if (type.endsWith("String") || type.endsWith("ExternalId")
                || type.endsWith("Url") || type.endsWith("Monolingualtext")) return "STRING";
        return "AUTO";
    }

    private static String cleanPid(String pid) {
        if (pid == null) return "";
        pid = pid.trim();
        if (pid.startsWith("wdt:")) pid = pid.substring(4);
        if (pid.startsWith("wd:")) pid = pid.substring(3);
        return pid.trim().toUpperCase();
    }
}
