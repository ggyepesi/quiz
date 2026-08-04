package quiz.source;

/**
 * A datasource handle: <em>where</em> an object's data comes from, and its native
 * id in that source.
 *
 * <p>Deliberately independent of {@link objectview.Viewable} — being the subject
 * of a datasource has nothing to do with being renderable. A {@code Source} is the
 * value of a domain object's {@code anchor} field (see {@link Sourced}); a
 * {@link SourceFactory} produces it (identify), a {@link SourceProducer} consumes
 * it (pull).</p>
 *
 * <p>{@link #id()} is the source's <em>native</em> id (a QID, a manual key, a
 * statement GUID) — what producers use to fetch data. It is NOT the owning
 * object's identity, which is stable and comes from the object's own
 * {@code getIdentifier()}; re-anchoring swaps the {@code Source} without moving
 * that identity.</p>
 *
 * <p>Provenance is a property of the descriptor's <em>type</em> ({@link
 * WikidataSource} = Wikidata, {@link ManualSource} = manual, a statement
 * descriptor = a Wikidata statement). A multi-source composite that holds several
 * of these can be added later without changing this contract.</p>
 */
public interface Source {

    /** The source system: {@code "wikidata"}, {@code "manual"}, … */
    String provenance();

    /** The object's native id in that source (a QID, a manual key, a statement
     *  GUID, …) — used by producers to fetch data, not the owning object's identity. */
    String id();
}
