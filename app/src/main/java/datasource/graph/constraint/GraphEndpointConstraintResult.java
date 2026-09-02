package datasource.graph.constraint;

import datasource.EntityRef;

import java.util.Set;

/** Inspectable outcome of applying one compatibility constraint to a candidate pair. */
public record GraphEndpointConstraintResult(
        Decision decision,
        Set<EntityRef> leftValues,
        Set<EntityRef> rightValues,
        Set<EntityRef> sharedValues,
        String reason) {

    public enum Decision { ACCEPTED, REJECTED, REVIEW }

    public GraphEndpointConstraintResult {
        leftValues = leftValues == null ? Set.of() : Set.copyOf(leftValues);
        rightValues = rightValues == null ? Set.of() : Set.copyOf(rightValues);
        sharedValues = sharedValues == null ? Set.of() : Set.copyOf(sharedValues);
        reason = reason == null ? "" : reason;
    }
}
