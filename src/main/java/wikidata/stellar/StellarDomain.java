package wikidata.stellar;

import wikidata.WikidataDownloadDomain;
import wikidata.WikidataEntityFilter;
import wikidata.query.WikidataRootQuery;

import java.util.List;

public class StellarDomain
        implements WikidataDownloadDomain<StellarRule> {

    @Override
    public WikidataRootQuery rootQuery() {
        return StellarDownloader.stellarRootQuery();
    }

    @Override
    public List<StellarRule> rules() {
        return StellarRules.defaultRules();
    }

    @Override
    public WikidataEntityFilter entityFilter() {
        return new StellarEntityFilter();
    }
}