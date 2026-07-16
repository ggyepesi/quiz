package objectview.field;

import quiz.Quizable;
import quiz.QuizableAdapter;
import objectview.annotations.Link;

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
    public boolean has(String name) {
        return QuizableAdapter.getField(object.getClass(), name) != null;
    }

    @Override
    public void write(String name, Object value) {
        Field f = QuizableAdapter.getField(object.getClass(), name);
        if (f == null) {
            throw new IllegalArgumentException(
                    "No field " + object.getClass().getName() + "." + name);
        }
        try {
            f.setAccessible(true);
            f.set(object, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Cannot set " + name + " on " + object, e);
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
            // Annotation-derived render hints — the metadata a dynamic field can't carry.
            boolean link = QuizableAdapter.isLinkField(f);
            Link linkAnn = link ? f.getAnnotation(Link.class) : null;
            out.add(FieldRef.of(f.getName(), kind, type.getSimpleName(),
                    reference, collection, QuizableAdapter.isMinorField(f),
                    QuizableAdapter.isQuizableInline(f),
                    link, linkAnn == null ? "" : linkAnn.text(),
                    QuizableAdapter.isProvenanceField(f),
                    QuizableAdapter.isQuizableReference(f)));
        }
        return out;
    }
}
