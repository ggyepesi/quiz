package wikidata.explore.advisor;

/** Read-only explanation of one field as seen by the compiled model. */
public record EffectiveFieldExplanation(
        String ownerClass,
        String fieldName,
        String valueShape,
        String source,
        String target,
        String role,
        String unavailableReason) {

    public EffectiveFieldExplanation {
        ownerClass = clean(ownerClass);
        fieldName = clean(fieldName);
        valueShape = clean(valueShape);
        source = clean(source);
        target = clean(target);
        role = clean(role);
        unavailableReason = clean(unavailableReason);
    }

    public boolean available() { return unavailableReason.isBlank(); }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
