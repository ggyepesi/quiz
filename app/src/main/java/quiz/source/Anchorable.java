package quiz.source;

/**
 * A carrier whose provenance anchor can be set or swapped <em>without touching
 * its identity</em>. Re-anchoring replaces the {@link SourceViewable} descriptor;
 * {@link objectview.Viewable#getIdentifier()} is unaffected, so the object stays
 * valid in pooled collections.
 *
 * <p>The anchor is named {@code anchor} rather than {@code source} deliberately:
 * {@code source} already names the reify back-reference field and structural
 * schema fields, so a distinct name avoids a rendering/schema collision.</p>
 */
public interface Anchorable {
    SourceViewable anchor();
    void anchor(SourceViewable anchor);
}
