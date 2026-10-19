package datasource.api.acquisition;

import datasource.EntityRef;

import java.util.List;

/**
 * A provider-neutral condition on a relation statement: the statement must carry
 * {@code relationId} with one of {@code values}. Providers translate this into their
 * native statement model (for Wikidata, a qualifier property and its allowed entities).
 */
public record RelationCondition(String relationId, List<EntityRef> values) {

    public RelationCondition {
        relationId = relationId == null ? "" : relationId.trim();
        values = List.copyOf(values == null ? List.of() : values);
        if (relationId.isBlank()) {
            throw new IllegalArgumentException("A relation condition needs a relation id");
        }
        if (values.isEmpty() || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("A relation condition needs allowed values");
        }
    }
}
