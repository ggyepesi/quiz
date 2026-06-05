package wikidata.countries;

import wikidata.WikidataEntity;
import wikidata.WikidataGroupedDownloader;
import wikidata.WikidataSparqlClient;
import wikidata.query.WikidataQueryBuilder;
import wikidata.query.WikidataRootQuery;

import java.io.File;
import java.util.*;

public class CountryGeoDownloader {

    public record CountryGeo(
            WikidataEntity country,
            Map<String, Set<WikidataEntity>> groups
    ) {
        public Set<WikidataEntity> group(String name) {
            return groups.getOrDefault(name, Set.of());
        }
    }

    private final WikidataGroupedDownloader<CountryGeoRule> grouped;

    public CountryGeoDownloader(WikidataSparqlClient client) {
        this.grouped = new WikidataGroupedDownloader<>(client, new CountryGeoEntityFilter());
    }

    public List<WikidataEntity> downloadCountries() throws Exception {
        return grouped.downloadRoots(countryRootQuery());
    }

    public Map<WikidataEntity, CountryGeo> download(
            Collection<WikidataEntity> countries,
            List<CountryGeoRule> rules
                                                   ) throws Exception {
        return download(countries, rules, null);
    }

    public Map<WikidataEntity, CountryGeo> download(
            Collection<WikidataEntity> countries,
            List<CountryGeoRule> rules,
            File checkpointFile
                                                   ) throws Exception {

        Map<WikidataEntity, WikidataGroupedDownloader.Downloaded> raw =
                grouped.download(countries, rules, checkpointFile);

        Map<WikidataEntity, CountryGeo> out = new LinkedHashMap<>();

        for (var e : raw.entrySet()) {
            out.put(e.getKey(),
                    new CountryGeo(
                            e.getValue().root(),
                            e.getValue().groups()));
        }

        return out;
    }

    public static WikidataRootQuery countryRootQuery() {
        String sparql = new WikidataQueryBuilder()
                .selectEntity("country")
                .where("?country wdt:P31/wdt:P279* wd:Q3624078 .")
                .orderBy("countryLabel")
                .where("?country rdfs:label ?countryLabel .")
                .where("FILTER(LANG(?countryLabel) = \"en\")")
                .build();

        return new WikidataRootQuery("country", sparql);
    }
}