package wikidata.explore.model;

/**
 * Source-independent definition of one domain field. ModelBuilder adds extraction
 * mapping around it; TransformApp can use the same definition for a newly declared
 * field before choosing how to populate it.
 */
public record FieldDefinition(
        String name,
        FieldType type,
        String entityClassName,
        FieldCardinality cardinality,
        FieldRenderMode renderMode) {

    public FieldDefinition {
        name = name == null ? "" : name.trim();
        type = type == null ? FieldType.AUTO : type;
        entityClassName = entityClassName == null ? "" : entityClassName.trim();
        cardinality = cardinality == null ? FieldCardinality.AUTO : cardinality;
        renderMode = renderMode == null ? FieldRenderMode.AUTO : renderMode;
    }
}
