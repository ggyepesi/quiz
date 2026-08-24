package datasource;

/** A provider-qualified entity identifier. No datasource is privileged as canonical. */
public record EntityRef(String namespace, String id) {

    /**
     * The namespace every Wikidata QID lives in.
     *
     * <p>Not a provider id, though it spells the same. A provider id names who fetches;
     * a namespace names where an identifier is meaningful, and more than one provider
     * can serve one namespace — a Wikibase mirror answers about wikidata: items without
     * being the wikidata provider.
     */
    public static final String WIKIDATA = "wikidata";

    public EntityRef {
        namespace = required(namespace, "Entity namespace is required");
        id = required(id, "Entity identifier is required");
    }

    public static EntityRef wikidata(String qid) {
        EntityRef ref = new EntityRef(WIKIDATA, qid);
        if (!wikidata.WikidataIds.isQid(ref.id)) {
            throw new IllegalArgumentException("Invalid Wikidata item QID: " + qid);
        }
        return ref;
    }

    public String qualifiedId() {
        return namespace + ':' + id;
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException(message);
        return normalized;
    }
}
