package quiz.fields;

import quiz.Quizable;
import quiz.QuizableAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link FieldSet} over a hand-written {@link Quizable}'s declared Java fields
 * (the "old" representation) — types come straight from the field's Java type.
 */
public final class ReflectionFieldSet implements FieldSet {

    private final Quizable object;

    public ReflectionFieldSet(Quizable object) {
        this.object = object;
    }

    @Override
    public Object read(String name) {
        Field f = QuizableAdapter.getField(object.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(object);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @Override
    public List<FieldRef> fields() {
        List<FieldRef> out = new ArrayList<>();
        for (Field f : QuizableAdapter.getAllFields(object.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> type = f.getType();
            boolean collection = Collection.class.isAssignableFrom(type) || type.isArray();
            // Direct entity reference; a collection-of-references would need the generic
            // element type (a later refinement — see ReflectionDomain.isReferenceField).
            boolean reference = Quizable.class.isAssignableFrom(type);
            FieldKind kind = collection ? FieldKind.COLLECTION
                    : reference ? FieldKind.REFERENCE
                    : FieldKind.ofClass(type);
            out.add(FieldRef.of(f.getName(), kind, type.getSimpleName(),
                    reference, collection, QuizableAdapter.isMinorField(f)));
        }
        return out;
    }
}
