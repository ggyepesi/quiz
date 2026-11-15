package datasource.api;

/**
 * A normal instance field contributed by a configured datasource.
 *
 * <p>The declaration is consumed when a runtime class/schema is generated; the
 * provider supplies the corresponding value from its acquired source carrier.</p>
 */
public interface DatasourceInstanceField {
    String name();
    String label();
    SourceValueSchema valueSchema();
    Object value(Object source);

    /** Datasource fields are origin metadata unless the declaration explicitly says
     *  that a durable side-output is ordinary, operation-visible data. */
    default objectview.field.FieldRole role() {
        return objectview.field.FieldRole.PROVENANCE;
    }

    /** Concrete value type for an entity-reference field. Generic source references
     *  keep the neutral Viewable default; structured side-outputs name their schema. */
    default Class<? extends objectview.Viewable> referenceType() {
        return objectview.Viewable.class;
    }
}
