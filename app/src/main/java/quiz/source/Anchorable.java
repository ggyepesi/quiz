package quiz.source;

/**
 * A carrier whose source identity is supplied by a replaceable anchor.
 * Re-anchoring intentionally changes {@link objectview.Viewable#getIdentifier()}.
 * Callers that index by logical identity must therefore reindex after the change;
 * Java collection safety relies on carrier object identity, not mutable source keys.
 *
 * <p>The anchor is named {@code anchor} rather than {@code source} deliberately:
 * {@code source} already names the reify back-reference field and structural
 * schema fields, so a distinct name avoids a rendering/schema collision.</p>
 */
public interface Anchorable {
    SourceViewable anchor();
    void anchor(SourceViewable anchor);
}
