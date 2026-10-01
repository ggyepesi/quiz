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
     * Whether {@code field} is a forward reference an inverse on {@code ownerClassName}
     * may read: an ENTITY field declared as that class, or as a role that may be
     * contextually represented as it (History's OfficeHolding.source is declared as the
     * role PositionHolder and holds Persons).
     *
     * <p>The candidate list is part of the decision, so it is asked here too. The editor
     * once listed exact class matches only; a stated inverse through a role was then not
     * among its choices, and the flush every Save performs wrote the choice back blank.
     */
    public static boolean referencesOwner(GeneratedProjectModel project,
                                          GeneratedFieldModel field,
                                          String ownerClassName) {
        if (field == null || ownerClassName == null
                || field.type() != datasource.schema.FieldType.ENTITY) return false;
        return ownerClassName.equals(field.entityClassName())
                || EntityRepresentations.mayRepresent(project,
                        field.entityClassName(), ownerClassName);
    }

    /**
     * @param explicitField    the author's declared inverse field, or blank
     * @param referencingOwner names of the forward fields that reference the inverse's
     *                         owning class, either directly or through a role that may be
     *                         contextually represented as that class. Representation is
     *                         therefore part of "references the owner" and can make a
     *                         blank inverse-field choice ambiguous; authors must then name
     *                         the intended field explicitly.
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
