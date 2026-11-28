package domain.transform;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.field.DynamicFields;
import objectview.field.FieldSchema;
import objectview.field.FieldSet;
import objectview.field.RecordTypes;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A generic mutable {@link objectview.Viewable} that carries its fields in a property map
 * ({@link DynamicFields}) and a settable type stamp — the domain-independent
 * counterpart of a snapshot object. The transform layer creates these for PROJECT
 * results, so it needn't depend on any particular backing (e.g. Wikidata).
 */
public class DynamicViewable extends ViewableAdapter implements DynamicFields {

    @Hidden
    private final String id;
    @DisplayField private final String name;

    private String type;
    private final Map<String, Object> fields = new LinkedHashMap<>();

    public DynamicViewable(String id, String name) {
        this.id = id == null ? "" : id;
        this.name = name == null || name.isBlank() ? this.id : name;
    }

    /** An identity-only reference to a caller-supplied domain type, not a new record type. */
    public static Viewable reference(String id, String name, String type) {
        var reference = new DynamicViewable(id, name);
        reference.type(type);
        return reference;
    }

    /** A sample of an already declared record type, filling each field from its first
     * non-null value so a sparse first row cannot hide the shape of the later rows. */
    public static Viewable recordSample(String id, String type, Collection<? extends Viewable> rows) {
        var schema = RecordTypes.schema(type);
        if (schema == null) throw new IllegalArgumentException("No declared record type: " + type);
        var sample = new DynamicViewable(id, type);
        sample.type(type);
        for (Viewable row : rows) {
            var fields = FieldSet.of(row);
            for (var field : schema.fields()) {
                if (sample.get(field.name()) == null) {
                    sample.put(field.name(), fields.read(field.name()));
                }
            }
        }
        return sample;
    }

    @Override public String getIdentifier() { return id; }
    @Override public String getDisplayName() { return name; }

    public void type(String type) { this.type = type; }

    @Override public String typeName() {
        return type == null || type.isBlank() ? getClass().getSimpleName() : type;
    }

    public Object get(String field) { return fields.get(field); }

    public void put(String field, Object value) {
        if (field != null && !field.isBlank() && value != null) {
            fields.put(field, value);
        }
    }

    @Override public Map<String, Object> dynamicFieldValues() { return fields; }

    /** Its type's declared fields ({@link RecordTypes}): what fields a
     * record has is its producer's statement, never read off the values it holds. */
    @Override public FieldSchema dynamicFieldSchema() {
        return RecordTypes.schema(typeName());
    }

    @Override public boolean equals(Object o) {
        return o instanceof DynamicViewable d && id.equals(d.id) && typeName().equals(d.typeName());
    }

    @Override public int hashCode() { return id.hashCode() * 31 + typeName().hashCode(); }
}
