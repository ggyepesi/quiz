package quiz.source;

import quiz.Quizable;

/**
 * Provenance of a {@link Quizable}: where its data came from and how to open
 * the original record.
 *
 * <p>Modelled as a {@link Quizable} so it renders like any other nested object
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
public interface Source extends Quizable {

    /** Canonical id of the record within this source (e.g. a Wikidata QID). */
    String sourceId();

    /** Human-openable URL of the original record, or "" if none. */
    String url();

    /** Short human label for the source ("Wikidata", "DBpedia", …). */
    String kind();
}
