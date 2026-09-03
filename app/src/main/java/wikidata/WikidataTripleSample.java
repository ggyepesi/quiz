package wikidata;

public record WikidataTripleSample(
        String direction,
        String propertyPid,
        String propertyLabel,
        String valueQid,
        String valueLabel,
        boolean media,
        String mediaUrl
) {
    public WikidataTripleSample(
            String direction,
            String propertyPid,
            String propertyLabel,
            String valueQid,
            String valueLabel) {
        this(direction, propertyPid, propertyLabel, valueQid, valueLabel,
             false, null);
    }

    @Override
    public String toString() {
        String property = LabelledId.display(propertyLabel, propertyPid);

        String value = valueLabel == null || valueLabel.isBlank()
                ? valueQid
                : valueLabel + (valueQid == null || valueQid.isBlank()
                                ? ""
                                : " (" + valueQid + ")");

        return property + " -> " + value;
    }
}