package wikidata.explore;

import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldRenderMode;
import datasource.schema.FieldType;

public class WikidataPropertyScore {

    public static FieldType fieldType(WikidataProperty p) {
        return switch (p.datatype()) {
            case "WikibaseItem"                         -> FieldType.ENTITY;
            case "CommonsMedia"                         -> FieldType.IMAGE;
            case "Quantity"                             -> FieldType.NUMBER;
            case "Time"                                 -> FieldType.DATE;
            case "String", "ExternalId", "Url",
                 "Monolingualtext"                      -> FieldType.STRING;
            default                                     -> FieldType.AUTO;
        };
    }

    /** A datasource suggestion, not stored cardinality. AUTO in the downloaded
     * property catalogue means the datatype did not justify a suggestion. */
    public static java.util.Optional<FieldCardinality> fieldCardinality(WikidataProperty p) {
        return switch (p.cardinality()) {
            case "SINGLE"     -> java.util.Optional.of(FieldCardinality.SINGLE);
            case "COLLECTION" -> java.util.Optional.of(FieldCardinality.COLLECTION);
            default           -> switch (p.datatype()) {
                case "CommonsMedia", "Quantity",
                     "Time", "GlobeCoordinate"          ->
                        java.util.Optional.of(FieldCardinality.SINGLE);
                default                                 -> java.util.Optional.empty();
            };
        };
    }

    public static FieldRenderMode renderMode(WikidataProperty p) {
        return switch (p.datatype()) {
            case "WikibaseItem"                         -> FieldRenderMode.REFERENCE;
            case "CommonsMedia"                         -> FieldRenderMode.INLINE;
            default                                     -> FieldRenderMode.AUTO;
        };
    }
}
