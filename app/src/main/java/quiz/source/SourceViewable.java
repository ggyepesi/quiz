package quiz.source;

import objectview.Viewable;

/**
 * A provenance descriptor: <em>where</em> an object's data comes from and its
 * native id in that source.
 *
 * <p>A {@code SourceViewable} is the <b>value of a domain object's {@code anchor}
 * field</b> — a swappable source identity. Re-anchoring an object replaces this
 * descriptor and therefore changes the owning object's logical identifier.</p>
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
     *  GUID, …). This becomes the owning object's identity while anchored. */
    String id();
}
