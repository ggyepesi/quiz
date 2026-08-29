package wikidata.explore;

import java.util.ArrayList;
import java.util.List;

/** Intrinsic, datasource-declared structural capabilities of one property. */
public final class PropertyStructuralHints {
    private PropertyStructuralHints() { }

    public static List<String> of(WikidataProperty property) {
        if (property == null) return List.of();
        List<String> hints = new ArrayList<>();
        if (isEntityDatatype(property.datatype())) hints.add("Entity relations");
        if (!property.superpropertyPids().isBlank()) hints.add("Specialized relations");
        if (!property.inversePropertyPids().isBlank()) hints.add("Paired directions");
        // COLLECTION is rare (37 of 13553) and therefore worth finding. SINGLE holds
        // two thirds of the catalogue, mostly external identifiers, so a group of it
        // narrows nothing: opening it is close to opening "All properties".
        if ("COLLECTION".equals(property.cardinality())) hints.add("Branching values");
        return List.copyOf(hints);
    }

    public static String describe(WikidataProperty property) {
        return String.join(" · ", of(property));
    }

    private static boolean isEntityDatatype(String datatype) {
        return datatype != null && datatype.startsWith("Wikibase");
    }
}
