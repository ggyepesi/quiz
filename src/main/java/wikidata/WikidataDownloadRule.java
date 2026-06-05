package wikidata;

public interface WikidataDownloadRule {
    String name();
    String itemVar();
    String queryFor(String rootQid);
}