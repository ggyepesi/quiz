package datasource.graph.constraint;

/** Compare values derived independently from the two endpoints of a candidate edge. */
public record GraphEndpointConstraint(
        GraphEndpointPath leftPath,
        GraphEndpointPath rightPath,
        MissingPolicy missingPolicy) {

    public enum MissingPolicy { REJECT, REVIEW }

    public GraphEndpointConstraint {
        if (leftPath == null || rightPath == null) {
            throw new IllegalArgumentException("Both endpoint paths are required");
        }
        missingPolicy = missingPolicy == null ? MissingPolicy.REVIEW : missingPolicy;
    }
}
