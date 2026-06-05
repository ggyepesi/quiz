package wikidata.query;

public record WikidataRootQuery(
        String rootVar,
        String sparql
) {}