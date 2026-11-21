package datasource.api.acquisition;

import work.Query;

/** One prepared, bounded population read supplied by a datasource operation. */
public record PopulationAcquisition(
        String namespace,
        String displayName,
        Query<AcquiredPopulation> query) {

    public PopulationAcquisition {
        namespace = required(namespace, "Population namespace is required");
        displayName = required(displayName, "Population display name is required");
        if (query == null) throw new IllegalArgumentException("Population query is required");
    }

    private static String required(String value, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isBlank()) throw new IllegalArgumentException(message);
        return clean;
    }
}
