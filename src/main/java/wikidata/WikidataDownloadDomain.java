package wikidata;

import wikidata.query.WikidataRootQuery;

import java.util.List;

public interface WikidataDownloadDomain<R extends WikidataDownloadRule> {
    WikidataRootQuery rootQuery();
    List<R> rules();
    WikidataEntityFilter entityFilter();
}