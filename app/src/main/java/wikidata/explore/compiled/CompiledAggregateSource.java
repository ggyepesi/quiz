package wikidata.explore.compiled;

import wikidata.explore.model.AggregateClassSource;
import java.util.List;

/**
 * Immutable, name-resolved aggregate recipe.
 *
 * <p>No missing-key policy: what becomes of a candidate whose key cannot be computed is
 * a question about the KEY, and the key belongs to the class's canonical spec — where
 * every other kind already answers it, and where this class answered it a second time
 * with a different enum and a different default.
 */
public record CompiledAggregateSource(
        String sourceClassId, String sourceClassName, String membersField, List<Key> keys) {
    public CompiledAggregateSource {
        sourceClassId = clean(sourceClassId);
        sourceClassName = clean(sourceClassName);
        membersField = clean(membersField);
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
    public boolean configured() {
        return !sourceClassName.isBlank() && !membersField.isBlank() && !keys.isEmpty();
    }
    public record Key(String targetField, String sourceField) {}
    public static CompiledAggregateSource from(AggregateClassSource source) {
        if (source == null) return null;
        return new CompiledAggregateSource(source.sourceClassId(), source.sourceClassName(),
                source.membersField(),
                source.keys().stream().map(k -> new Key(k.targetField(), k.sourceField())).toList());
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
