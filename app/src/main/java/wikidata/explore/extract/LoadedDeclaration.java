package wikidata.explore.extract;

/**
 * A field declaration whose property HAS been fetched, recorded in the snapshot.
 *
 * <p>Emptiness alone cannot say why a field has no value: nobody asked, or Wikidata has
 * nothing to give. Without the difference, every run re-asks for every entity that
 * legitimately has no answer — for P734 that is most people, forever. Recording the
 * declaration says "this one has been done", so a later run fetches only what is new.
 *
 * @param covered legacy/readable count of entities the load ran over
 * @param coveredQids exact entity identities covered by the load; a count cannot tell
 *                    that one entity replaced another while the pool size stayed equal
 */
public record LoadedDeclaration(
        String className, String fieldName, String propertyPid, int covered,
        java.util.List<String> coveredQids) {

    /** Backward-compatible constructor for old snapshots and callers. Such a declaration
     *  has no trustworthy identity coverage, so Enrich re-checks it once and replaces it
     *  with the exact-QID form. */
    public LoadedDeclaration(
            String className, String fieldName, String propertyPid, int covered) {
        this(className, fieldName, propertyPid, covered, java.util.List.of());
    }

    public LoadedDeclaration(
            String className, String fieldName, String propertyPid,
            java.util.Collection<String> coveredQids) {
        this(className, fieldName, propertyPid,
                coveredQids == null ? 0 : coveredQids.size(),
                coveredQids == null ? java.util.List.of()
                        : new java.util.ArrayList<>(coveredQids));
    }

    public LoadedDeclaration {
        className = clean(className);
        fieldName = clean(fieldName);
        propertyPid = clean(propertyPid);
        covered = Math.max(0, covered);
        java.util.LinkedHashSet<String> qids = new java.util.LinkedHashSet<>();
        if (coveredQids != null) {
            coveredQids.stream().filter(wikidata.WikidataIds::isQid).forEach(qids::add);
        }
        coveredQids = java.util.List.copyOf(qids);
    }

    /** Identity of the declaration, ignoring how much it covered. */
    public String key() {
        return className + "." + fieldName + ":" + propertyPid;
    }

    public static String key(String className, String fieldName, String propertyPid) {
        return clean(className) + "." + clean(fieldName) + ":" + clean(propertyPid);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
