package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.explore.model.StableIdentity;
import wikidata.explore.compiled.CompiledAggregateSource;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.AggregateClassSource;
import wikidata.explore.model.AggregateIdentity;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/** Builds configured aggregate classes offline from already materialized records. */
public final class ModelAggregates {
    private ModelAggregates() {}

    public static int apply(CompiledProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log) {
        if (model == null) return 0;
        List<Recipe> recipes = model.classes().stream().filter(CompiledClass::aggregateClass)
                .map(c -> recipe(c.className(), c.aggregateSource())).toList();
        return apply(recipes, pool, log);
    }

    public static int apply(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            GenerationLog log) {
        if (model == null) return 0;
        List<Recipe> recipes = model.classes().stream()
                .filter(c -> c.aggregateSource() != null && c.aggregateSource().configured())
                .map(c -> recipe(c.className(), c.aggregateSource())).toList();
        return apply(recipes, pool, log);
    }

    private static int apply(List<Recipe> recipes, List<WikidataDynamicObject> pool,
            GenerationLog log) {
        if (recipes.isEmpty()) return 0;
        var aggregateTypes = recipes.stream().map(Recipe::targetType).collect(
                java.util.stream.Collectors.toSet());
        pool.removeIf(o -> o != null && o.directClassNames().stream()
                .anyMatch(aggregateTypes::contains));
        int made = 0;
        for (Recipe recipe : ordered(recipes)) {
            LinkedHashMap<List<String>, Bucket> groups = new LinkedHashMap<>();
            int excludedMissing = 0;
            for (WikidataDynamicObject source : List.copyOf(pool)) {
                if (source == null || !source.directClassNames().contains(recipe.sourceType())) {
                    continue;
                }
                List<Object> values = recipe.keys().stream()
                        .map(key -> source.get(key.sourceField())).toList();
                if (recipe.missingKeyPolicy() == AggregateClassSource.MissingKeyPolicy.EXCLUDE
                        && values.stream().anyMatch(ModelAggregates::missing)) {
                    excludedMissing++;
                    continue;
                }
                List<String> key = values.stream().map(ModelAggregates::stableValue).toList();
                groups.computeIfAbsent(key, ignored -> new Bucket(values, new ArrayList<>()))
                        .members().add(source);
            }
            for (var entry : groups.entrySet()) {
                String id = AggregateIdentity.identifier(recipe.targetType(), entry.getKey());
                Bucket bucket = entry.getValue();
                String label = bucket.values().stream().map(ModelAggregates::display)
                        .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(" — "));
                WikidataDynamicObject aggregate = new WikidataDynamicObject(id,
                        label.isBlank() ? recipe.targetType() : label);
                aggregate.type(recipe.targetType());
                for (int i = 0; i < recipe.keys().size(); i++) {
                    aggregate.put(recipe.keys().get(i).targetField(), bucket.values().get(i));
                }
                aggregate.put(recipe.membersField(), new ArrayList<>(bucket.members()));
                pool.add(aggregate);
                made++;
            }
            if (log != null) log.message("Aggregate " + recipe.sourceType() + " → "
                    + recipe.targetType() + ": " + groups.size() + " group(s) by "
                    + recipe.keys().stream().map(Key::sourceField)
                            .collect(java.util.stream.Collectors.joining(" + "))
                    + (excludedMissing == 0 ? "" : "; " + excludedMissing
                            + " source record(s) excluded by missing-key policy") + ".\n");
        }
        return made;
    }

    private static List<Recipe> ordered(List<Recipe> recipes) {
        LinkedHashMap<String, Recipe> byTarget = new LinkedHashMap<>();
        recipes.forEach(r -> byTarget.put(r.targetType(), r));
        List<Recipe> out = new ArrayList<>();
        java.util.Set<String> visiting = new java.util.HashSet<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Recipe recipe : recipes) visit(recipe, byTarget, visiting, done, out);
        return out;
    }

    private static void visit(Recipe recipe, java.util.Map<String, Recipe> byTarget,
            java.util.Set<String> visiting, java.util.Set<String> done, List<Recipe> out) {
        if (done.contains(recipe.targetType())) return;
        if (!visiting.add(recipe.targetType())) {
            throw new IllegalStateException("Aggregate dependency cycle at " + recipe.targetType());
        }
        Recipe dependency = byTarget.get(recipe.sourceType());
        if (dependency != null) visit(dependency, byTarget, visiting, done, out);
        visiting.remove(recipe.targetType());
        done.add(recipe.targetType());
        out.add(recipe);
    }

    private static Recipe recipe(String target, CompiledAggregateSource source) {
        return new Recipe(target, source.sourceClassName(), source.membersField(),
                source.keys().stream().map(k -> new Key(k.targetField(), k.sourceField())).toList(),
                source.missingKeyPolicy());
    }
    private static Recipe recipe(String target, AggregateClassSource source) {
        return new Recipe(target, source.sourceClassName(), source.membersField(),
                source.keys().stream().map(k -> new Key(k.targetField(), k.sourceField())).toList(),
                source.missingKeyPolicy());
    }
    private static boolean missing(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> values && values.isEmpty();
    }
    private static String stableValue(Object value) {
        return StableIdentity.of(value);
    }
    private static String display(Object value) {
        if (value instanceof Viewable v) return v.getDisplayName();
        return value == null ? "" : value.toString();
    }
    private record Key(String targetField, String sourceField) {}
    private record Recipe(String targetType, String sourceType, String membersField,
                          List<Key> keys, AggregateClassSource.MissingKeyPolicy missingKeyPolicy) {}
    private record Bucket(List<Object> values, List<WikidataDynamicObject> members) {}
}
