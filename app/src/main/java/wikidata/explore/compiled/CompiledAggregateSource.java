package wikidata.explore.compiled;

import wikidata.explore.model.AggregateClassSource;
import java.util.List;

/** Immutable, name-resolved aggregate recipe. */
public record CompiledAggregateSource(
        String sourceClassId, String sourceClassName, String membersField, List<Key> keys,
        AggregateClassSource.MissingKeyPolicy missingKeyPolicy) {
    public CompiledAggregateSource {
        sourceClassId = clean(sourceClassId);
        sourceClassName = clean(sourceClassName);
        membersField = clean(membersField);
        keys = keys == null ? List.of() : List.copyOf(keys);
        missingKeyPolicy = missingKeyPolicy == null
                ? AggregateClassSource.MissingKeyPolicy.EXCLUDE : missingKeyPolicy;
    }
    public boolean configured() {
        return !sourceClassName.isBlank() && !membersField.isBlank() && !keys.isEmpty();
    }
    public record Key(String targetField, String sourceField) {}
    public static CompiledAggregateSource from(AggregateClassSource source) {
        if (source == null) return null;
        return new CompiledAggregateSource(source.sourceClassId(), source.sourceClassName(),
                source.membersField(),
                source.keys().stream().map(k -> new Key(k.targetField(), k.sourceField())).toList(),
                source.missingKeyPolicy());
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
