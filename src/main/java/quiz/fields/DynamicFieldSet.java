package quiz.fields;

import quiz.DynamicFields;
import quiz.Quizable;
import quiz.transform.ui.FieldKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A {@link FieldSet} over a {@link DynamicFields} object's property map (the "new"
 * representation — e.g. {@code WikidataDynamicObject}). Types are inferred from the
 * VALUES here; a schema-backed type source ({@code ProductSchema}/{@code
 * FieldTypeSource}) can override this so a field is typed even when its value is
 * null — that wiring is the substance of #87, kept out of this seam for now.
 */
public final class DynamicFieldSet implements FieldSet {

    private final DynamicFields object;

    public DynamicFieldSet(DynamicFields object) {
        this.object = object;
    }

    @Override
    public Object read(String name) {
        return object.dynamicFieldValues().get(name);
    }

    @Override
    public List<FieldRef> fields() {
        List<FieldRef> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : object.dynamicFieldValues().entrySet()) {
            out.add(fieldRef(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static FieldRef fieldRef(String name, Object value) {
        boolean collection = value instanceof Collection<?> || (value != null && value.getClass().isArray());
        boolean reference = value instanceof Quizable
                || (value instanceof Collection<?> c && anyQuizable(c));
        String typeLabel = value == null ? null : value.getClass().getSimpleName();
        return FieldRef.of(name, FieldKind.ofValue(value), typeLabel, reference, collection, false);
    }

    private static boolean anyQuizable(Collection<?> c) {
        for (Object o : c) {
            if (o instanceof Quizable) {
                return true;
            }
        }
        return false;
    }
}
