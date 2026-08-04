package quiz.source;

import objectview.Viewable;

/**
 * A provenance descriptor: <em>where</em> an object's data comes from and its
 * native id in that source.
 *
 * <p>A {@code SourceViewable} is the <b>value of a domain object's {@code source}
 * field</b> — a swappable anchor, never the object's own identity. Re-anchoring
 * an object (e.g. a manual country gaining a Wikidata population) replaces this
 * descriptor with another; the object's {@code getIdentifier()} does not move, so
 * pooled collections stay valid.</p>
 *
 * <p>Provenance is a property of the descriptor's <em>type</em> ({@link
 * WikidataViewable} = Wikidata, {@link ManualViewable} = manual, a statement
 * descriptor = a Wikidata statement). A multi-source composite that holds several
 * of these can be added later without changing this contract or the field.</p>
 */
public interface SourceViewable extends Viewable {

    /** The source system: {@code "wikidata"}, {@code "manual"}, … */
    String provenance();

    /** The object's native id in that source (a QID, a manual key, a statement
     *  GUID, …). Distinct from the owning object's stable identity. */
    String id();
}
