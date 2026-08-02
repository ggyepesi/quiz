package quiz.source;

/**
 * Application source base. The field/rendering contract lives in objectview;
 * concrete source kinds remain in the host application.
 *
 * <p>Modelled as an {@link objectview.Viewable} so it renders like any other nested object
 * — a collapsed reference chip on the owning card (see {@code Card}),
 * expandable to its source-specific internals. Different sources carry
 * different internals ({@link WikidataSource} holds a QID + a wiki URL; a
 * future {@code DbpediaSource} would hold a DBpedia URI, a {@code SerpApiSource}
 * a search-engine reference), but all answer the same three questions.
 *
 * <p>This is a presentation/provenance abstraction layered <i>on top of</i> the
 * owner's identity — it does not replace the canonical id (the QID stays the
 * key for canonicalization, snapshots, navigation, and web serving).
 */
public abstract class Source extends objectview.provenance.Source {
    protected Source(String kind, String sourceId, String recordUrl) {
        super(kind, sourceId, recordUrl);
    }
}
