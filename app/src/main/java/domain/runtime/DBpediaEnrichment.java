package domain.runtime;

import wikidata.WikidataIds;

import domain.datasource.EntityRef;
import domain.datasource.SourceRef;
import domain.datasource.evidence.ContentDigest;
import domain.datasource.evidence.EvidenceFragment;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.SourceDocument;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.time.Instant;

/**
 * Fills a class's DBpedia-sourced fields (Wikipedia infobox data that isn't in
 * Wikidata — brightest star, number of main stars, …) by querying DBpedia,
 * joined to the already-extracted entities by {@code owl:sameAs} → their
 * Wikidata QID. One batched query per field; values are merged onto the same
 * dynamic objects, so they flow to both the desktop mapper and the web/snapshot.
 */
public class DBpediaEnrichment {

    private static final int BATCH = 100;

    public int enrich(
            List<WikidataDynamicObject> roots,
            GeneratedClassModel clazz,
            WikidataSparqlClient dbpedia,
            Consumer<String> log,
            List<FieldRequest> dbFields) throws Exception {

        if (roots == null || roots.isEmpty() || clazz == null) {
            return 0;
        }

        if (dbFields == null || dbFields.isEmpty()) {
            return 0;
        }

        Map<String, WikidataDynamicObject> byQid = new HashMap<>();
        for (WikidataDynamicObject o : roots) {
            if (o != null && o.qid() != null && WikidataIds.isQid(o.qid())) {
                byQid.put(o.qid(), o);
            }
        }
        if (byQid.isEmpty()) {
            return 0;
        }

        List<String> qids = new ArrayList<>(byQid.keySet());
        log.accept("\nDBpedia enrichment: " + dbFields.size() + " field(s) for "
                + qids.size() + " " + clazz.className() + "(s)\n");

        int total = 0;
        for (FieldRequest declaration : dbFields) {
            GeneratedFieldModel f = declaration.field();
            String prop = declaration.property();
            int merged = 0;
            boolean captureSources = byQid.values().stream()
                    .anyMatch(object -> object.capturesFieldSource(f.name()));

            for (int i = 0; i < qids.size(); i += BATCH) {
                List<String> batch = qids.subList(i, Math.min(i + BATCH, qids.size()));
                String sparql = query(prop, batch);

                List<WikidataBinding> rows = dbpedia.query(sparql);
                Map<String, SourceDocument> documents = captureSources
                        ? sourceDocuments(rows, prop) : Map.of();
                for (WikidataBinding b : rows) {
                    String wd = qidFromUri(b.value("wd"));
                    if (wd == null) {
                        continue;
                    }
                    WikidataDynamicObject obj = byQid.get(wd);
                    if (obj == null) {
                        continue;
                    }
                    if (declaration.fillOnlyMissing() && obj.get(f.name()) != null) {
                        continue;
                    }
                    String label = b.value("valLabel");
                    String val = label != null && !label.isBlank()
                            ? label : b.value("val");
                    if (val != null && !val.isBlank()) {
                        String value = val.trim();
                        if (obj.capturesFieldSource(f.name())) {
                            SourceDocument document = documents.get(b.value("dbr"));
                            if (document == null) {
                                throw new IllegalStateException(
                                        "DBpedia returned a value without its record for " + wd);
                            }
                            SourceRef source = new SourceRef("DBpedia", "",
                                    "http://dbpedia.org/property/" + prop, prop);
                            obj.recordFieldClaim(f.name(), source, new ExtractedClaim(
                                    EntityRef.wikidata(wd), prop, value, null,
                                    List.of(EvidenceFragment.excerpt(document, prop, value)),
                                    "DBpedia property query", "1", "", 1, List.of()));
                        }
                        obj.merge(f.name(), value);
                        merged++;
                    }
                }
            }

            log.accept("  " + f.name() + " <- dbp:" + prop + ": "
                    + merged + " value(s)\n");
            total += merged;
        }
        return total;
    }

    private static Map<String, SourceDocument> sourceDocuments(List<WikidataBinding> rows,
            String property) {
        String retrievedAt = Instant.now().toString();
        return rows.stream().filter(row -> row.value("dbr") != null && !row.value("dbr").isBlank())
                .collect(Collectors.groupingBy(row -> row.value("dbr"), Collectors.collectingAndThen(
                        Collectors.toList(), recordRows -> {
                            String record = recordRows.getFirst().value("dbr");
                            String returnedValues = recordRows.stream()
                                    .map(row -> List.of(
                                            java.util.Objects.toString(row.value("val"), ""),
                                            java.util.Objects.toString(row.value("valLabel"), "")).toString())
                                    .distinct().sorted().collect(Collectors.joining("\n"));
                            return new SourceDocument("DBpedia", record, record, record, "",
                                    ContentDigest.sha256(property + "\n" + returnedValues), retrievedAt);
                        })));
    }

    public record FieldRequest(
            GeneratedFieldModel field, String property, boolean fillOnlyMissing) {
        public FieldRequest {
            if (field == null) throw new IllegalArgumentException("field is required");
            property = property == null ? "" : property.trim();
            if (property.isBlank()) throw new IllegalArgumentException("property is required");
        }
    }

    private static String query(String prop, List<String> qids) {
        StringBuilder values = new StringBuilder();
        for (String q : qids) {
            values.append("<http://www.wikidata.org/entity/").append(q).append("> ");
        }
        return """
               PREFIX dbp: <http://dbpedia.org/property/>
               PREFIX owl: <http://www.w3.org/2002/07/owl#>
               PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
               SELECT ?wd ?dbr ?val ?valLabel WHERE {
                 VALUES ?wd { %s }
                 ?dbr owl:sameAs ?wd .
                 ?dbr dbp:%s ?val .
                 OPTIONAL { ?val rdfs:label ?valLabel . FILTER(LANG(?valLabel) = "en") }
               }
               """.formatted(values.toString().trim(), prop);
    }

    private static String qidFromUri(String uri) {
        if (uri == null) {
            return null;
        }
        int i = uri.lastIndexOf('/');
        String tail = i >= 0 ? uri.substring(i + 1) : uri;
        return WikidataIds.isQid(tail) ? tail : null;
    }
}
