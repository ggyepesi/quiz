package quiz.transform.ui;

import wikidata.WikidataIds;

import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.query.core.Datasource;
import wikidata.explore.query.core.Query;
import wikidata.explore.query.core.QueryContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Query definitions for DBpedia-backed curation suggestions. */
final class DBpediaLookup {

    private DBpediaLookup() {}

    record ImageHit(String resourceUrl, String imageUrl) { }

    static Query<List<String>> values(String qid, String property) {
        if (qid == null || !WikidataIds.isQid(qid)
                || property == null || property.isBlank()) {
            throw new IllegalArgumentException("A Wikidata QID and DBpedia property are required");
        }
        String sparql = ("""
                PREFIX dbo: <http://dbpedia.org/ontology/>
                PREFIX dbp: <http://dbpedia.org/property/>
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?val ?valLabel WHERE {
                  ?dbr owl:sameAs <http://www.wikidata.org/entity/%1$s> .
                  { ?dbr dbo:%2$s ?val } UNION { ?dbr dbp:%2$s ?val }
                  OPTIONAL { ?val rdfs:label ?valLabel . FILTER(LANG(?valLabel) = "en") }
                } LIMIT 25
                """).formatted(qid, property);

        return query(
                "Check DBpedia " + property,
                "DBpedia property lookup",
                Map.of("qid", qid, "property", property),
                sparql,
                rows -> {
                    LinkedHashSet<String> out = new LinkedHashSet<>();
                    for (WikidataBinding binding : rows) {
                        String label = binding.value("valLabel");
                        String value = label != null && !label.isBlank()
                                ? label : readable(binding.value("val"));
                        if (value != null && !value.isBlank()) {
                            out.add(value.trim());
                        }
                    }
                    return new ArrayList<>(out);
                });
    }

    static Query<List<ImageHit>> images(String qid, String label) {
        String subject;
        Map<String, String> parameters = new LinkedHashMap<>();
        if (qid != null && WikidataIds.isQid(qid)) {
            subject = "?candidate owl:sameAs <http://www.wikidata.org/entity/" + qid + "> .";
            parameters.put("qid", qid);
        } else if (label != null && !label.isBlank()) {
            subject = "?candidate rdfs:label " + sparqlString(label) + "@en .";
            parameters.put("label", label);
        } else {
            throw new IllegalArgumentException("A Wikidata QID or display label is required");
        }
        String sparql = ("""
                PREFIX foaf: <http://xmlns.com/foaf/0.1/>
                PREFIX dbo: <http://dbpedia.org/ontology/>
                PREFIX owl: <http://www.w3.org/2002/07/owl#>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?dbr ?img WHERE {
                  %1$s
                  OPTIONAL { ?candidate dbo:wikiPageRedirects ?redirect }
                  BIND(COALESCE(?redirect, ?candidate) AS ?dbr)
                  ?dbr (foaf:depiction|dbo:thumbnail) ?img
                } LIMIT 10
                """).formatted(subject);

        return query(
                "Find DBpedia image",
                "DBpedia image lookup",
                parameters,
                sparql,
                rows -> {
                    LinkedHashMap<String, ImageHit> out = new LinkedHashMap<>();
                    for (WikidataBinding binding : rows) {
                        String image = binding.value("img");
                        if (image != null && !image.isBlank()) {
                            String resource = binding.value("dbr");
                            ImageHit hit = new ImageHit(
                                    resource == null ? "" : resource.trim(), image.trim());
                            out.putIfAbsent(hit.imageUrl(), hit);
                        }
                    }
                    return new ArrayList<>(out.values());
                });
    }

    private static <T> Query<List<T>> query(
            String purpose,
            String description,
            Map<String, String> parameters,
            String sparql,
            java.util.function.Function<List<WikidataBinding>, List<T>> mapper) {

        return new Query<>() {
            @Override public String purpose() {
                return purpose;
            }

            @Override public String skeleton() {
                return description;
            }

            @Override public String queryType() {
                return "DBpedia SPARQL";
            }

            @Override public String description() {
                return description;
            }

            @Override public Map<String, String> parameters() {
                return parameters;
            }

            @Override public Datasource datasource() {
                return Datasource.DBPEDIA;
            }

            @Override public List<T> execute(QueryContext context) throws Exception {
                WikidataSparqlClient client = context.sparql(datasource());
                if (client == null) {
                    throw new IllegalStateException("No DBpedia SPARQL client configured");
                }
                return context.step(
                        description,
                        "DBpedia SPARQL",
                        description,
                        parameters,
                        step -> {
                            step.request(sparql);
                            List<T> result = mapper.apply(client.query(sparql));
                            step.summary(result.size() + " candidate(s)");
                            return result;
                        });
            }

            @Override public int rowCount(List<T> result) {
                return result == null ? 0 : result.size();
            }

            @Override public String summary(List<T> result) {
                return rowCount(result) + " candidate(s)";
            }
        };
    }

    private static String sparqlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String readable(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("http")) {
            int slash = value.lastIndexOf('/');
            return (slash >= 0 ? value.substring(slash + 1) : value).replace('_', ' ');
        }
        return value;
    }
}
