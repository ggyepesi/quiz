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

    /** The instances to run the view over. */
    Collection<? extends Quizable> instances();

    /** The universe class for the {@code ClassTransformPlan} (kept-instances plan). */
    Class<? extends Quizable> universe();
}
