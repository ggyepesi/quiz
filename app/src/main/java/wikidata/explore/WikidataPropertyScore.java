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

    public static FieldCardinality fieldCardinality(WikidataProperty p) {
        return switch (p.cardinality()) {
            case "SINGLE"     -> FieldCardinality.SINGLE;
            case "COLLECTION" -> FieldCardinality.COLLECTION;
            default           -> switch (p.datatype()) {
                case "CommonsMedia", "Quantity",
                     "Time", "GlobeCoordinate"          -> FieldCardinality.SINGLE;
                default                                 -> FieldCardinality.AUTO;
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
