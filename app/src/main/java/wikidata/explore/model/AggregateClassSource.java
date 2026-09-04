package wikidata.explore.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares an offline class whose instances group records of another modeled class.
 * Key mappings are explicit target/source pairs: the aggregate owns the target field;
 * the source record merely supplies its value. The members field receives the grouped
 * source records themselves.
 */
public final class AggregateClassSource {
    private String sourceClassName = "";
    private String sourceClassId = "";
    private String membersField = "";
    private MissingKeyPolicy missingKeyPolicy = MissingKeyPolicy.EXCLUDE;
    private final List<Key> keys = new ArrayList<>();

    public AggregateClassSource() {}

    public AggregateClassSource(String sourceClassName, String membersField) {
        sourceClassName(sourceClassName);
        membersField(membersField);
    }

    public String sourceClassName() { return clean(sourceClassName); }
    public void sourceClassName(String value) {
        sourceClassName = clean(value);
        sourceClassId = "";
    }
    public String sourceClassId() { return DeclarationIds.clean(sourceClassId); }
    public void sourceClassId(String value) { sourceClassId = DeclarationIds.clean(value); }
    void sourceClassReference(String id, String name) {
        sourceClassId = DeclarationIds.clean(id);
        sourceClassName = clean(name);
    }
    public String membersField() { return clean(membersField); }
    public void membersField(String value) { membersField = clean(value); }
    /**
     * The target/source pairs, which are now a RENAME TABLE and not the identity.
     *
     * <p>Which fields identify an aggregate, and in what order, is
     * {@code canonical().keyFields()} — the same place every other construct keeps it.
     * What is left here is the half only an aggregate has: each of its own fields is
     * grouped from a differently-named field on the source record, and applying that
     * rename is construction. Milestone 4 already drew that line by keeping construction
     * in ModelAggregates; this puts the identity on the other side of it.
     */
    public List<Key> keys() { return keys; }

    /** Where an aggregate's own field takes its grouping value from. */
    public String sourceFieldFor(String targetField) {
        String target = clean(targetField);
        return keys.stream()
                .filter(key -> key != null && key.targetField().equals(target))
                .map(Key::sourceField)
                .findFirst().orElse("");
    }
    public MissingKeyPolicy missingKeyPolicy() {
        return missingKeyPolicy == null ? MissingKeyPolicy.EXCLUDE : missingKeyPolicy;
    }
    public void missingKeyPolicy(MissingKeyPolicy value) {
        missingKeyPolicy = value == null ? MissingKeyPolicy.EXCLUDE : value;
    }
    public boolean configured() {
        return !sourceClassName().isBlank() && !membersField().isBlank() && !keys.isEmpty();
    }
    public AggregateClassSource copy() {
        AggregateClassSource copy = new AggregateClassSource(sourceClassName(), membersField());
        copy.sourceClassId = sourceClassId;
        copy.missingKeyPolicy = missingKeyPolicy();
        keys.stream().filter(java.util.Objects::nonNull)
                .forEach(key -> copy.keys.add(new Key(key.targetField(), key.sourceField())));
        return copy;
    }
    public record Key(String targetField, String sourceField) {
        public Key {
            targetField = clean(targetField);
            sourceField = clean(sourceField);
        }
    }
    public enum MissingKeyPolicy {
        /** A source record missing any grouping value is not aggregated. */
        EXCLUDE,
        /** Missing values deliberately form an explicit incomplete group. */
        GROUP
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
