package quiz.source;

/**
 * A carrier that is the subject of a datasource: it holds a replaceable
 * {@link Source} anchor.
 *
 * <p>Re-anchoring does NOT change the carrier's identity: {@link
 * objectview.Viewable#getIdentifier()} is stable and comes from the carrier
 * itself; the anchor only carries the source's native id (a resolved qid) for
 * enrichment. So an already-pooled carrier can be re-anchored safely.</p>
 *
 * <p>The anchor is named {@code anchor} rather than {@code source} deliberately:
 * {@code source} already names the reify back-reference field and structural
 * schema fields, so a distinct name avoids a rendering/schema collision.</p>
 */
public interface Sourced {
    Source anchor();
    void anchor(Source anchor);
}
