package wikidata.explore;

public record WikidataProperty(
        String pid,
        String label,
        String description,
        String datatype,
        String cardinality,
        String superpropertyPids,
        String inversePropertyPids
) {
    /** Backward-compatible shape used by the existing catalogue and callers. */
    public WikidataProperty(
            String pid, String label, String description,
            String datatype, String cardinality) {
        this(pid, label, description, datatype, cardinality, "", "");
    }

    public WikidataProperty {
        superpropertyPids = superpropertyPids == null ? "" : superpropertyPids;
        inversePropertyPids = inversePropertyPids == null ? "" : inversePropertyPids;
    }
}
