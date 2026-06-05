package wikidata;

import java.io.File;
import java.util.List;
import java.util.Map;

public class WikidataDomainDownloader<R extends WikidataDownloadRule> {

    private final WikidataGroupedDownloader<R> grouped;

    public WikidataDomainDownloader(
            WikidataSparqlClient client,
            WikidataEntityFilter filter
                                   ) {
        this.grouped = new WikidataGroupedDownloader<>(
                client,
                filter);
    }

    public List<WikidataEntity> downloadRoots(
            WikidataDownloadDomain<R> domain
                                             ) throws Exception {

        return grouped.downloadRoots(domain.rootQuery());
    }

    public Map<WikidataEntity,
            WikidataGroupedDownloader.Downloaded> download(
            WikidataDownloadDomain<R> domain,
            File checkpointFile
                                                          ) throws Exception {

        List<WikidataEntity> roots =
                grouped.downloadRoots(domain.rootQuery());

        return grouped.download(
                roots,
                domain.rules(),
                checkpointFile);
    }

    public Map<WikidataEntity,
            WikidataGroupedDownloader.Downloaded> download(
            WikidataDownloadDomain<R> domain,
            List<WikidataEntity> roots,
            File checkpointFile
                                                          ) throws Exception {

        return grouped.download(
                roots,
                domain.rules(),
                checkpointFile);
    }
}