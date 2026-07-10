package quiz.fields;

import quiz.DynamicFields;
import quiz.Quizable;

import java.util.List;

/**
 * The fields of a domain object, backing-agnostic: declared Java fields via
 * reflection ({@link ReflectionFieldSet}) or a dynamic property map ({@link
 * DynamicFieldSet}). This is the ONE interface the machinery reads — {@code
 * QuizablePanel}, the config editors, the search index, the sort keys — so it never
 * branches on {@code instanceof DynamicFields}. Nothing is migrated onto it yet;
 * this is the seam. See #87.
 *
 * <p>Reads are single-level; dotted paths are composed by reading a reference and
 * wrapping its value with {@link #of} again (what {@code FieldAccess.getPath} does).
 */
public interface FieldSet {

    /** The object's fields, in a stable order. */
    List<FieldRef> fields();

    /** This instance's value for {@code name} (null if absent). */
    Object read(String name);

    /** A backing-appropriate FieldSet: the dynamic property map when present, else
     *  declared-field reflection. */
    static FieldSet of(Quizable object) {
        return object instanceof DynamicFields dynamic
                ? new DynamicFieldSet(dynamic)
                : new ReflectionFieldSet(object);
    }
}
