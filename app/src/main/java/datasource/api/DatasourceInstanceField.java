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
}
