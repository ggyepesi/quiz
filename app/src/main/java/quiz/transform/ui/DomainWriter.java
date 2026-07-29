package quiz.transform.ui;

import objectview.Viewable;

import java.util.Collection;

/** Persists a transform result (the current view's members) as a first-class
 *  domain, returning a human-readable confirmation. Implemented outside this
 *  package (e.g. to a Wikidata snapshot + dataset registry). */
public interface DomainWriter {
    String save(String name, Collection<? extends Viewable> members) throws Exception;

    /**
     * Persists the values together with their authoritative schema. The default
     * keeps existing writers source-compatible; snapshot writers use the schema
     * to retain null and empty declared fields across a round trip.
     */
    default String save(String name, Collection<? extends Viewable> members,
                        DomainModel schema) throws Exception {
        return save(name, members);
    }
}
