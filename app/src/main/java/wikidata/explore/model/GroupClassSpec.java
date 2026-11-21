package wikidata.explore.model;

/**
 * Declares that each instance of a class is a group whose members are held by one
 * ordinary collection field.
 *
 * <p>This is presentation structure on the class, not a population producer: the
 * class may still be generated from a source, graph, aggregate or transformation in
 * the usual way. Consumers adapt the same instances to {@code ViewableGroup}; they do
 * not infer group semantics from a class name or from a field that happens to be a
 * collection.
 */
public record GroupClassSpec(String membersField) {
    public GroupClassSpec {
        membersField = clean(membersField);
        if (membersField.isBlank()) {
            throw new IllegalArgumentException("A group members field is required");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
