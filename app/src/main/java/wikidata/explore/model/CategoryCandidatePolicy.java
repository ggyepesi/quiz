package wikidata.explore.model;

/** What generation does with a value inferred from a configured category relation. */
public enum CategoryCandidatePolicy {
    REVIEW("Retain for review"),
    EVIDENCE_ONLY("Corroborate existing values only");

    private final String label;
    CategoryCandidatePolicy(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
