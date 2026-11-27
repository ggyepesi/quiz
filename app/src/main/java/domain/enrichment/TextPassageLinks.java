package domain.enrichment;

import domain.config.FieldSourceBindings;
import domain.config.GeneratedProjectModel;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.runtime.WikidataDynamicObject;
import objectview.Viewable;
import objectview.field.LinkedTextSequence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Presentation of the text and references a passage recipe already puts on an instance. */
public final class TextPassageLinks {
    private TextPassageLinks() { }

    private record TextField(String type, String field) { }

    /** The recipe, not a story class or a field-name convention, supplies both field paths. */
    public static void install(GeneratedProjectModel model,
            Collection<? extends WikidataDynamicObject> instances) {
        if (model == null || instances.isEmpty()) return;
        Map<TextField, Set<String>> references = new LinkedHashMap<>();
        for (var owner : model.classes()) {
            for (var field : owner.fields()) {
                for (var binding : FieldSourceBindings.bindings(field, SourceBindingSlot.CANDIDATE_EVIDENCE)) {
                    if (!WikipediaDatasourceProvider.textPassageInstanceOperation(binding)) continue;
                    var spec = WikipediaDatasourceProvider.textPassageInstances(binding);
                    if (!spec.configured() || spec.referencesField().isBlank()) continue;
                    references.computeIfAbsent(new TextField(field.entityClassName(), spec.textField()),
                            ignored -> new LinkedHashSet<>()).add(spec.referencesField());
                }
            }
        }
        Map<String, Map<String, String>> byType = new LinkedHashMap<>();
        references.forEach((field, targets) -> {
            if (targets.size() == 1) byType.computeIfAbsent(field.type(), ignored -> new LinkedHashMap<>())
                    .put(field.field(), targets.iterator().next());
        });
        if (byType.isEmpty()) return;
        for (var instance : instances) {
            var fields = byType.get(instance.typeName());
            if (fields == null) continue;
            fields.forEach((textKey, referencesKey) -> instance.runtimeFieldProjection(textKey,
                    stored -> link(stored.get(textKey), stored.get(referencesKey))));
        }
    }

    static Object link(Object rawText, Object references) {
        if (!(rawText instanceof String text) || text.isBlank()) return rawText;
        List<Viewable> targets = new ArrayList<>();
        if (references instanceof Viewable target) {
            targets.add(target);
        } else if (references instanceof Collection<?> values) {
            for (Object value : values) if (value instanceof Viewable target) targets.add(target);
        }
        if (targets.isEmpty()) return text;
        var occurrences = LoadedNameMatches.of(targets, LoadedNameMatches::labelAndAliases).occurrences(text);
        if (occurrences.isEmpty()) return text;

        // Start with the longest match at a given offset, so "Thor" does not split
        // "Thor the Mighty" when both are accepted targets. Preserve the source spelling.
        var sorted = occurrences.stream().sorted(Comparator.comparingInt(LoadedNameMatches.Hit::offset)
                .thenComparing(Comparator.comparingInt((LoadedNameMatches.Hit hit) ->
                        hit.phrase().length()).reversed())).toList();
        List<LinkedTextSequence.Part> parts = new ArrayList<>();
        int at = 0;
        for (var hit : sorted) {
            int start = hit.offset();
            if (start < at) continue;
            if (start > at) parts.add(LinkedTextSequence.text(text.substring(at, start)));
            at = start + hit.phrase().length();
            parts.add(LinkedTextSequence.reference(text.substring(start, at), hit.candidate()));
        }
        if (at < text.length()) parts.add(LinkedTextSequence.text(text.substring(at)));
        return LinkedTextSequence.of(parts.toArray(LinkedTextSequence.Part[]::new));
    }
}
