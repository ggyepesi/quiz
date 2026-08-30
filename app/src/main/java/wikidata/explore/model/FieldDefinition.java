package wikidata.explore.model;

import datasource.schema.FieldType;

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
        FieldRenderMode renderMode,
        boolean unclassedEntity) {

    /** An ENTITY field whose values keep their identity and label but whose class the
     *  model does not name — declared, not merely left blank. Wikidata answers many
     *  properties with items (P734 a family-name item), and a class was the only way to
     *  say so: empty classes appeared purely to satisfy the question "which class?".
     *  This says "an entity, unclassed" instead, so a class exists only when something
     *  is declared on it, served from it, or classified into it. */
    public FieldDefinition(String name, FieldType type, String entityClassName,
                           FieldCardinality cardinality, FieldRenderMode renderMode) {
        this(name, type, entityClassName, cardinality, renderMode, false);
    }

    public FieldDefinition {
        name = name == null ? "" : name.trim();
        type = type == null ? FieldType.AUTO : type;
        entityClassName = entityClassName == null ? "" : entityClassName.trim();
        cardinality = cardinality == null ? FieldCardinality.AUTO : cardinality;
        renderMode = renderMode == null ? FieldRenderMode.AUTO : renderMode;
        // The two are exclusive: naming a class IS classing the value.
        if (unclassedEntity) entityClassName = "";
    }
}
