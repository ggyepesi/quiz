package quiz.transform.ui;

import objectview.Viewable;

import java.util.Collection;
import domain.DomainModel;

/** Persists a transform result (the current view's members) as a first-class
 *  domain, returning a human-readable confirmation. Implemented outside this
 *  package (e.g. to a Wikidata snapshot + dataset registry). */
public interface DomainWriter {
    /** Exact point-of-action disclosure shown by the shared Save UI. */
    default String describeSave(String name, Collection<? extends Viewable> members,
                                DomainModel schema) {
        int count = members == null ? 0 : members.size();
        return "Save domain \"" + name + "\" with " + count + " instance"
                + (count == 1 ? "" : "s") + " and types "
                + (schema == null ? java.util.List.of() : schema.servedTypes()) + ".";
    }

    /** Persists the values together with their authoritative schema. */
    String save(String name, Collection<? extends Viewable> members,
                DomainModel schema) throws Exception;
}
