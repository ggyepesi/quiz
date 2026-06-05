package wikidata.stellar;

import wikidata.WikidataEntity;
import wikidata.WikidataGroupedDownloader;
import wikidata.WikidataSparqlClient;
import wikidata.query.WikidataQueryBuilder;
import wikidata.query.WikidataRootQuery;

import java.io.File;
import java.util.*;

public class StellarDownloader {

    public record StellarObjectData(
            WikidataEntity object,
            Map<String, Set<WikidataEntity>> groups
    ) {
        public Set<WikidataEntity> group(String name) {
            return groups.getOrDefault(name, Set.of());
        }
    }

    private final WikidataGroupedDownloader<StellarRule> grouped;

    public StellarDownloader(WikidataSparqlClient client) {
        this.grouped = new WikidataGroupedDownloader<>(client, new StellarEntityFilter());
    }

    public List<WikidataEntity> downloadRootObjects() throws Exception {
        return grouped.downloadRoots(stellarRootQuery());
    }

    public Map<WikidataEntity, StellarObjectData> download(
            Collection<WikidataEntity> roots,
            List<StellarRule> rules
                                                          ) throws Exception {
        return download(roots, rules, null);
    }

    public Map<WikidataEntity, StellarObjectData> download(
            Collection<WikidataEntity> roots,
            List<StellarRule> rules,
            File checkpointFile
                                                          ) throws Exception {

        Map<WikidataEntity, WikidataGroupedDownloader.Downloaded> raw =
                grouped.download(roots, rules, checkpointFile);

        Map<WikidataEntity, StellarObjectData> out = new LinkedHashMap<>();

        for (var e : raw.entrySet()) {
            out.put(e.getKey(),
                    new StellarObjectData(
                            e.getValue().root(),
                            e.getValue().groups()));
        }

        return out;
    }

    public static WikidataRootQuery stellarRootQuery() {
        String sparql = new WikidataQueryBuilder()
                .selectEntity("object")
                .values("type",
                        "Q8928"
                        )
                .where("?object wdt:P31 ?type .")

                .orderBy("objectLabel")
                .build();

        return new WikidataRootQuery("object", sparql);
    }
}