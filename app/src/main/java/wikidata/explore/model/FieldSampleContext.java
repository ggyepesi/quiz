package wikidata.explore.model;


public record FieldSampleContext(
        GeneratedClassModel ownerClass,
        GeneratedFieldModel field,
        /** Project-aware population type, including evidence-derived kinds. */
        String ownerTypeQid
) {
}
