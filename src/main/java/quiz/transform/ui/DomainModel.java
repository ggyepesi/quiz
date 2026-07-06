package quiz.transform.ui;

import quiz.Quizable;

import java.util.Collection;
import java.util.List;

/**
 * A domain the transform workbench operates over: its instances plus the schema
 * (types and, per type, field names and shapes) the operation slots filter on.
 * Decoupled from any particular backing so the SAME workbench runs over a Wikidata
 * snapshot ({@link SnapshotDomain}) or the hand-written {@code Quizable} domains —
 * Nobel, State, SportTeam — via reflection ({@link ReflectionDomain}).
 */
public interface DomainModel {

    List<String> types();

    /** The fields of a type — possibly NESTED paths (e.g. {@code nominee.name}) —
     *  each carrying its dotted path and leaf shape (reference/collection). */
    List<DomainField> fields(String type);

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
    default quiz.ui.viewconfig.FieldTypeSource fieldTypes(String type) {
        return null;
    }

    /** The instances to run the view over. */
    Collection<? extends Quizable> instances();

    /** The universe class for the {@code ClassTransformPlan} (kept-instances plan). */
    Class<? extends Quizable> universe();
}
