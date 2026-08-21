package datasource;

/** A provider-qualified entity identifier. No datasource is privileged as canonical. */
public record EntityRef(String namespace, String id) {
    public EntityRef {
        namespace = required(namespace, "Entity namespace is required");
        id = required(id, "Entity identifier is required");
    }

    public static EntityRef wikidata(String qid) {
        EntityRef ref = new EntityRef("wikidata", qid);
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
