package wikidata.stellar;

import wikidata.*;

import java.io.File;
import java.util.Map;

public class StellarDownloaderMain {
    public static void main(String[] args) throws Exception {
        File checkpoint =
                new File("src/main/resources/wikidata/stellar/stellar.checkpoint.json");

        File outFile =
                new File("src/main/resources/wikidata/stellar/stellar.json");

        try (WikidataSparqlClient client =
                     new WikidataSparqlClient(
                             "QuizProject/1.0 (ggyepesi@gmail.com)",
                             1)) {

            StellarDomain domain = new StellarDomain();

            WikidataDomainDownloader<StellarRule> downloader =
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