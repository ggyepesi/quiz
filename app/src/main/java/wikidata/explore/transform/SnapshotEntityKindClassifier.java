package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.WikidataIds;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataObjectGraph;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies entity-kind rules from property values already persisted in a snapshot. */
public final class SnapshotEntityKindClassifier {
    private SnapshotEntityKindClassifier() { }

    public record Result(int classified, int unknown, int withoutStoredEvidence) { }

    private record Producer(String ownerClass, String fieldName) { }

    public static Result apply(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> targetPool,
            Collection<WikidataDynamicObject> evidencePool,
            GenerationLog log) {
        if (model == null || targetPool == null || evidencePool == null) {
            return new Result(0, 0, 0);
        }
        List<EntityKindRule> rules = model.entityKindRules().stream()
                .filter(EntityKindRule::isConfigured)
                .filter(rule -> model.findClass(rule.className()) != null).toList();
        if (rules.isEmpty()) return new Result(0, 0, 0);

        Map<String, List<Producer>> producers = producers(model);
        Map<String, Map<String, Set<String>>> evidence =
                evidence(model, evidencePool, producers);
        Map<String, List<Viewable>> roles = RoleSelections.materialize(model, targetPool);
        LinkedHashMap<String, WikidataDynamicObject> candidates = new LinkedHashMap<>();
        roles.values().stream().flatMap(Collection::stream)
                .filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast)
                .filter(value -> WikidataIds.isQid(value.qid()))
                .forEach(value -> candidates.putIfAbsent(value.qid(), value));
        Map<String, List<WikidataDynamicObject>> copiesByQid = new LinkedHashMap<>();
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(targetPool)) {
            if (object != null && WikidataIds.isQid(object.qid())) {
                copiesByQid.computeIfAbsent(object.qid(), ignored -> new ArrayList<>())
                        .add(object);
            }
        }

        int classified = 0, unknown = 0, withoutEvidence = 0;
        for (WikidataDynamicObject candidate : candidates.values()) {
            Map<String, Set<String>> byPid = evidence.get(candidate.qid());
            boolean hasEvidence = false;
            boolean matched = false;
            for (EntityKindRule rule : rules) {
                Set<String> values = byPid == null ? null : byPid.get(rule.propertyPid());
                if (values == null || values.isEmpty()) continue;
                hasEvidence = true;
                if (values.stream().anyMatch(rule.evidenceQids()::contains)) {
                    copiesByQid.getOrDefault(candidate.qid(), List.of())
                            .forEach(copy -> copy.assignClass(rule.className()));
                    matched = true;
                }
            }
            if (matched) {
                classified++;
                for (WikidataDynamicObject copy :
                        copiesByQid.getOrDefault(candidate.qid(), List.of())) {
                    for (String legacyRole : RoleSelections.legacyRoleClassNames(model)) {
                        copy.removeClass(legacyRole);
                    }
                    String carrier = carrier(copy.directClassNames(), model);
                    if (carrier != null) {
                        copy.type(carrier);
                        copy.typeKey(carrier);
                    }
                }
            } else {
                unknown++;
                if (!hasEvidence) withoutEvidence++;
            }
        }
        if (log != null) {
            log.message("Snapshot entity-kind classification: " + classified
                    + " classified, " + unknown + " unknown (" + withoutEvidence
                    + " without stored evidence).\n");
        }
        return new Result(classified, unknown, withoutEvidence);
    }

    private static Map<String, List<Producer>> producers(GeneratedProjectModel model) {
        Map<String, List<Producer>> out = new LinkedHashMap<>();
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field == null || field.type() != FieldType.ENTITY) continue;
                String pid = field.mapping().propertyPid();
                if (WikidataIds.isPid(pid)) {
                    out.computeIfAbsent(pid, ignored -> new ArrayList<>())
                            .add(new Producer(owner.className(), field.name()));
                }
            }
        }
        return out;
    }

    private static Map<String, Map<String, Set<String>>> evidence(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> pool,
            Map<String, List<Producer>> producers) {
        Map<String, Map<String, Set<String>>> out = new LinkedHashMap<>();
        Map<String, Set<String>> membersByClass = membersByClass(model, pool);
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(pool)) {
            if (object == null || !WikidataIds.isQid(object.qid())) continue;
            for (Map.Entry<String, List<Producer>> byPid : producers.entrySet()) {
                for (Producer producer : byPid.getValue()) {
                    if (!object.directClassNames().contains(producer.ownerClass())
                            && !membersByClass.getOrDefault(
                                    producer.ownerClass(), Set.of()).contains(object.qid())) {
                        continue;
                    }
                    Set<String> found = new LinkedHashSet<>();
                    collect(object.get(producer.fieldName()), found);
                    if (!found.isEmpty()) {
                        out.computeIfAbsent(object.qid(), ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(byPid.getKey(), ignored -> new LinkedHashSet<>())
                                .addAll(found);
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, Set<String>> membersByClass(
            GeneratedProjectModel model, Collection<WikidataDynamicObject> pool) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Map<String, List<Viewable>> materialized = RoleSelections.materialize(model, pool);
        for (wikidata.explore.model.RoleSelection role : RoleSelections.definitions(model)) {
            for (Viewable member : materialized.getOrDefault(role.key(), List.of())) {
                if (member instanceof WikidataDynamicObject object
                        && WikidataIds.isQid(object.qid())) {
                    out.computeIfAbsent(role.name(), ignored -> new LinkedHashSet<>())
                            .add(object.qid());
                }
            }
        }
        return out;
    }

    private static void collect(Object value, Set<String> into) {
        if (value instanceof WikidataDynamicObject object) {
            if (WikidataIds.isQid(object.qid())) into.add(object.qid());
        } else if (value instanceof Collection<?> values) {
            values.forEach(item -> collect(item, into));
        }
    }

    private static String carrier(Set<String> classes, GeneratedProjectModel model) {
        String best = null;
        int bestDepth = -1;
        for (String candidate : classes) {
            if (WikidataDynamicObject.isInternalClassName(candidate)) continue;
            int depth = 0;
            Set<String> seen = new java.util.HashSet<>();
            String current = candidate;
            while (current != null && seen.add(current)) {
                depth++;
                GeneratedClassModel cls = model.findClass(current);
                current = cls == null || !cls.hasBase() ? null : cls.baseClassName();
            }
            if (depth > bestDepth || (depth == bestDepth
                    && (best == null || candidate.compareTo(best) < 0))) {
                best = candidate;
                bestDepth = depth;
            }
        }
        return best;
    }
}
