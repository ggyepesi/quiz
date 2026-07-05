package quiz.transform.ui;

import quiz.Quizable;

import java.util.Collection;

/** Persists a transform result (the current view's members) as a first-class
 *  domain, returning a human-readable confirmation. Implemented outside this
 *  package (e.g. to a Wikidata snapshot + dataset registry). */
public interface DomainWriter {
    String save(String name, Collection<? extends Quizable> members) throws Exception;
}
