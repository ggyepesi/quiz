package wikidata.countries;

import wikidata.*;

import java.io.File;
import java.util.Map;

public class CountryGeoDownloaderMain {
    public static void main(String[] args) throws Exception {
        File checkpoint =
                new File("src/main/resources/wikidata/countries/country-geo.checkpoint.json");

        File outFile =
                new File("src/main/resources/wikidata/countries/country-geo.json");

        try (WikidataSparqlClient client =
                     new WikidataSparqlClient(
                             "QuizProject/1.0 (ggyepesi@gmail.com)",
                             1)) {

            CountryGeoDomain domain = new CountryGeoDomain();

            WikidataDomainDownloader<CountryGeoRule> downloader =
                    new WikidataDomainDownloader<>(
                            client,
                            domain.entityFilter());

            Map<WikidataEntity, WikidataGroupedDownloader.Downloaded> data =
                    downloader.download(domain, checkpoint);

            outFile.getParentFile().mkdirs();
            WikidataGroupedJson.write(data, outFile);

            System.out.println("Wrote " + outFile.getAbsolutePath());
        }
    }
}