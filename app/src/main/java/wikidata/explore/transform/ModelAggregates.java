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

    private static int apply(List<Recipe> recipes, List<WikidataDynamicObject> pool,
            GenerationLog log) {
        if (recipes.isEmpty()) return 0;
        var aggregateTypes = recipes.stream().map(Recipe::targetType).collect(
                java.util.stream.Collectors.toSet());
        pool.removeIf(o -> o != null && o.directClassNames().stream()
                .anyMatch(aggregateTypes::contains));
        int made = 0;
        for (Recipe recipe : ordered(recipes)) {
            // Grouping runs through the common engine. It was a LinkedHashMap loop with
            // its own stable-key call and its own missing-key handling, which is how
            // "several candidates are one thing" came to have two implementations that
            // agreed only by coincidence. Construction stays here: an aggregate makes an
            // instance of ANOTHER class, which is what keeps it a separate step rather
            // than a kind of reduction.
            List<canonical.Candidate> sources = new ArrayList<>();
            for (WikidataDynamicObject source : List.copyOf(pool)) {
                if (source != null && source.directClassNames().contains(recipe.sourceType())) {
                    sources.add(new SourceCandidate(source, recipe));
                }
            }
            // Grouped by the SOURCE field each of the aggregate's own key fields reads
            // from — the rename applied in the other direction. The order is the
            // canonical key's, which is where an aggregate's identity now lives.
            canonical.CanonicalizationPlan plan = new canonical.CanonicalizationPlan(
                    recipe.targetType(),
                    recipe.keys().stream()
                            .map(key -> canonical.KeyComponent.field(key.sourceField()))
                            .toList(),
                    missingKeyPolicy(recipe.missingKeyPolicy()),
                    java.util.Map.of(MEMBERS, canonical.Reduction.UNION_DISTINCT));

            canonical.KeyedReduction.Result reduced = canonical.KeyedReduction.reduce(
                    plan, sources, WikidataCandidates.stableForm());
            int excludedMissing = reduced.unkeyed().size();

            for (canonical.KeyedReduction.Instance group : reduced.instances()) {
                List<Object> values = recipe.keys().stream()
                        .map(key -> group.values().get(key.sourceField())).toList();
                List<String> stableKey = values.stream()
                        .map(ModelAggregates::stableValue).toList();
                String id = AggregateIdentity.identifier(recipe.targetType(), stableKey);
                @SuppressWarnings("unchecked")
                List<Object> members = (List<Object>) group.values()
                        .getOrDefault(MEMBERS, List.of());
                String label = values.stream().map(ModelAggregates::display)
                        .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining(" — "));
                WikidataDynamicObject aggregate = new WikidataDynamicObject(id,
                        label.isBlank() ? recipe.targetType() : label);
                aggregate.type(recipe.targetType());
                // The rename from source field to target field is construction, and
                // stays here: the engine grouped by what the SOURCE says, and the
                // aggregate names those values its own way.
                for (int i = 0; i < recipe.keys().size(); i++) {
                    aggregate.put(recipe.keys().get(i).targetField(), values.get(i));
                }
                aggregate.put(recipe.membersField(), new ArrayList<>(members));
                pool.add(aggregate);
                made++;
            }
            if (log != null) log.message("Aggregate " + recipe.sourceType() + " → "
                    + recipe.targetType() + ": " + reduced.instances().size() + " group(s) by "
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
    private static String stableValue(Object value) {
        return StableIdentity.of(value);
    }
    private static String display(Object value) {
        if (value instanceof Viewable v) return v.getDisplayName();
        return value == null ? "" : value.toString();
    }
    private record Key(String targetField, String sourceField) {}
    /** The field a source candidate reports itself under, so the engine's union collects
     *  the members. Not a modelled field: it never reaches an aggregate, which stores
     *  them under the recipe's own members field. */
    private static final String MEMBERS = "__members";

    /**
     * A source record, seen as a candidate for the aggregate it will join.
     *
     * <p>It reports its grouping values under the SOURCE field names, because that is
     * what the aggregate groups by, and reports itself under {@link #MEMBERS} so that
     * "the members are the union of what fell in this group" is said with the ordinary
     * reducer rather than by collecting a bucket alongside.
     */
    private record SourceCandidate(WikidataDynamicObject object, Recipe recipe)
            implements canonical.Candidate {
        @Override public String className() { return recipe.targetType(); }
        @Override public Object value(String fieldPath) {
            return MEMBERS.equals(fieldPath) ? object : object.get(fieldPath);
        }
        @Override public String structuralIdentity(canonical.KeyComponent.Kind kind) {
            return "";
        }
    }

    /**
     * One vocabulary for one concept. An aggregate had EXCLUDE and GROUP; the shared
     * names are REJECT_CANDIDATE and INCOMPLETE_GROUP, and they mean the same two things.
     */
    private static canonical.MissingKeyPolicy missingKeyPolicy(
            AggregateClassSource.MissingKeyPolicy policy) {
        return policy == AggregateClassSource.MissingKeyPolicy.EXCLUDE
                ? canonical.MissingKeyPolicy.REJECT_CANDIDATE
                : canonical.MissingKeyPolicy.INCOMPLETE_GROUP;
    }

    private record Recipe(String targetType, String sourceType, String membersField,
                          List<Key> keys, AggregateClassSource.MissingKeyPolicy missingKeyPolicy) {}
}
