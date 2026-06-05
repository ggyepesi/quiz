package wikidata.countries;

import wikidata.WikidataDownloadDomain;
import wikidata.WikidataEntityFilter;
import wikidata.query.WikidataRootQuery;

import java.util.List;

public class CountryGeoDomain
        implements WikidataDownloadDomain<CountryGeoRule> {

    @Override
    public WikidataRootQuery rootQuery() {
        return CountryGeoDownloader.countryRootQuery();
    }

    @Override
    public List<CountryGeoRule> rules() {
        return CountryGeoRules.defaultRules();
    }

    @Override
    public WikidataEntityFilter entityFilter() {
        return new CountryGeoEntityFilter();
    }
}