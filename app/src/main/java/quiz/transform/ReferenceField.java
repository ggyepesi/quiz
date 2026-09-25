package quiz.transform;

import objectview.Viewable;
import objectview.field.FieldAccess;
import objectview.field.FieldPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The entities a stored reference field holds, whatever shape carries them. */
final class ReferenceField {
    private ReferenceField() { }

    static List<Viewable> values(Viewable node, FieldPath path) {
        List<Viewable> out = new ArrayList<>();
        if (node != null && path != null) {
            collect(FieldAccess.getPathValues(node, path), out);
        }
        return out;
    }

    private static void collect(Object value, List<Viewable> out) {
        if (value instanceof Viewable viewable) {
            out.add(viewable);
        } else if (value instanceof Collection<?> values) {
            values.stream().filter(Viewable.class::isInstance)
                    .map(Viewable.class::cast).forEach(out::add);
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object element = java.lang.reflect.Array.get(value, i);
                if (element instanceof Viewable viewable) out.add(viewable);
            }
        }
    }
}
