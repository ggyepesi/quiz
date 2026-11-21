package datasource.api.acquisition;

import datasource.EntityRef;

import java.util.List;

/**
 * The bounded entity identities returned by one self-acquiring class population.
 *
 * <p>Unresolved source records are named separately: they are evidence that the source
 * was examined, never anonymous entities invented to make the population look complete.
 */
public record AcquiredPopulation(
        List<EntityRef> members,
        List<String> unresolvedSourceRecords) {

    public AcquiredPopulation {
        members = List.copyOf(members == null ? List.of() : members);
        unresolvedSourceRecords = List.copyOf(
                unresolvedSourceRecords == null ? List.of() : unresolvedSourceRecords);
    }
}
