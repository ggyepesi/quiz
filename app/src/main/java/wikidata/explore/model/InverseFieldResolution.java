package wikidata.explore.model;

import java.util.List;

/**
 * Which forward field an INVERT reads, decided once.
 *
 * <p>Generation and validation both have to answer this, and answering it twice let
 * them disagree: the resolver preferred a property match and inverted happily, while
 * validation counted only class references and reported the same model ambiguous. A
 * model that generated correctly failed to validate.
 *
 * <p>The candidates are supplied by the caller because they come from two different
 * shapes — a compiled class and an authored one — but the DECISION is here, so
 * "validation accepts exactly what generation can resolve" is true by construction
 * rather than by two implementations agreeing.
 */
public final class InverseFieldResolution {

    private InverseFieldResolution() { }

    /**
     * @param explicitField    the author's declared inverse field, or blank
     * @param referencingOwner names of the forward fields that reference the inverse's
     *                         owning class
     * @param alsoMatchingPid  the subset of those that also carry the inverse's property
     * @return the forward field name, or null when the question has no single answer
     */
    public static String resolve(String explicitField,
                                 List<String> referencingOwner,
                                 List<String> alsoMatchingPid) {
        String explicit = explicitField == null ? "" : explicitField.trim();
        if (!explicit.isBlank()) {
            return referencingOwner.contains(explicit) ? explicit : null;
        }
        if (alsoMatchingPid.size() == 1) return alsoMatchingPid.get(0);
        return alsoMatchingPid.isEmpty() && referencingOwner.size() == 1
                ? referencingOwner.get(0) : null;
    }
}
