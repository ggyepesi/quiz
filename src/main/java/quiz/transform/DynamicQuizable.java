package quiz.transform;

import objectview.annotations.NotViewableField;
import objectview.field.DynamicFields;
import quiz.QuizableAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A generic mutable {@link quiz.Quizable} that carries its fields in a property map
 * ({@link DynamicFields}) and a settable type stamp — the domain-independent
 * counterpart of a snapshot object. The transform layer creates these for PROJECT
 * results, so it needn't depend on any particular backing (e.g. Wikidata).
 */
public class DynamicQuizable extends QuizableAdapter implements DynamicFields {

    @NotViewableField
    private final String id;
    @NotViewableField
    private final String name;

    private String type;
    private final Map<String, Object> fields = new LinkedHashMap<>();

    public DynamicQuizable(String id, String name) {
        this.id = id == null ? "" : id;
        this.name = name == null || name.isBlank() ? this.id : name;
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

    @Override public boolean equals(Object o) {
        return o instanceof DynamicQuizable d && id.equals(d.id) && typeName().equals(d.typeName());
    }

    @Override public int hashCode() { return id.hashCode() * 31 + typeName().hashCode(); }
}
