package quiz.transform.ui;

import objectview.Viewable;

import java.util.Collection;
import domain.DomainModel;

/** Persists a transform result (the current view's members) as a first-class
 *  domain, returning a human-readable confirmation. Implemented outside this
 *  package (e.g. to a Wikidata snapshot + dataset registry). */
public interface DomainWriter {
    /** Persists the values together with their authoritative schema. */
    String save(String name, Collection<? extends Viewable> members,
                DomainModel schema) throws Exception;
}
