package quiz.transform.ui;

import objectview.viewconfig.FieldTypeSource;
import objectview.Viewable;
import objectview.field.FieldSchema;

import java.util.Collection;
import java.util.List;

/**
 * A domain the transform workbench operates over: its instances plus the schema
 * (types and, per type, field names and shapes) the operation slots filter on.
 * Decoupled from any particular backing so the SAME workbench runs over a Wikidata
 * snapshot ({@link SnapshotDomain}) or the hand-written {@code Viewable} domains —
 * Nobel, State, SportTeam — via reflection ({@link ReflectionDomain}).
 */
public interface DomainModel {

    List<String> types();

    /** The fields of a type — possibly NESTED paths (e.g. {@code nominee.name}) —
     *  each carrying its dotted path and leaf shape (reference/collection). */
    List<DomainField> fields(String type);

    /**
     * The canonical immutable top-level schema for {@code type} — the single source of
     * truth for field structure (names, shapes, reference targets, structural roles).
     *
     * <p>A native source OVERRIDES this; {@link #structuralFields}, {@link #fieldTypes} and
     * {@link DomainSchemas#fields} then PROJECT from it. A small/legacy source instead keeps
     * this default and implements {@link #fields}/{@link #fieldTypes} directly, which
     * {@link DomainSchemas#fromLegacy} adapts into a schema.
     *
     * <p>CONTRACT — implement exactly ONE side. Do NOT leave this default while ALSO
     * projecting {@code fieldTypes()}/{@code structuralFields()} from {@code fieldSchema()}:
     * fromLegacy would read a projection of itself and recurse. (It fails loud with a clear
     * cause rather than overflowing the stack — see {@link DomainSchemas#fromLegacy}.)
     */
    default FieldSchema fieldSchema(String type) {
        return DomainSchemas.fromLegacy(this, type);
    }

    /**
     * Top-level field names of {@code type} that are STRUCTURAL — plumbing or
     * provenance the pickers should skip (they stay on the data). This is the
     * generic seam for backing-specific exceptions: a bridge translates its
     * domain conventions (e.g. "a statement class's auto-created reify back-ref")
     * into plain field names here, so the workbench applies them without any
     * knowledge of the backing.
     */
    default java.util.Set<String> structuralFields(String type) {
        return java.util.Set.of();
    }

    /**
     * Optional authoritative field types for a dynamic sample of {@code type} —
     * the config editors' picker labels, cardinality and structural-hiding, from
     * a compiled model rather than sample reflection. Null (default) reflects the
     * sample, as before.
     */
    default FieldTypeSource fieldTypes(String type) {
        return null;
    }

    /** A representative instance for enumerating {@code type}'s fields. The default is
     *  the first matching instance; a persisted snapshot may return a small shape sample
     *  built from its saved field graph instead of scanning its instance pool. */
    default Viewable representativeSample(String type) {
        for (Viewable q : instances()) {
            if (q != null && type != null && type.equals(q.typeName())) {
                return q;
            }
        }
        return null;
    }

    /** The instances to run the view over. */
    Collection<? extends Viewable> instances();

    /** The universe class for the {@code ClassTransformPlan} (kept-instances plan). */
    Class<? extends Viewable> universe();
}
