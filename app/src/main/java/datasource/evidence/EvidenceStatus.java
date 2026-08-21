package datasource.evidence;

/** Why previously reviewed evidence should (or should not) be reviewed again. */
public enum EvidenceStatus {
    CURRENT,
    STALE_SOURCE,
    STALE_RECIPE,
    STALE_MODEL,
    SOURCE_UNAVAILABLE
}
