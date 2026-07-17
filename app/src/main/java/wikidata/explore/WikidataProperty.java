package wikidata.explore;

public record WikidataProperty(
        String pid,
        String label,
        String description,
        String datatype,
        String cardinality
) {
}