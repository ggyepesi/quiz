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

    /**
     * @param newlyClassified the objects this pass actually restamped — the answer to
     *        "which ones", which the count alone could never give. A settled pool should
     *        yield none: every pass reporting the same thousands is a pass that cannot
     *        see its own previous work, and reading that as progress is how a copier
     *        losing one flag went unnoticed for as long as it did.
     */
    public record Result(
            int classified,
            int unknown,
            int withoutStoredEvidence,
            Set<String> withoutStoredEvidenceQids,
            List<WikidataDynamicObject> newlyClassified) {
        public Result(int classified, int unknown, int withoutStoredEvidence) {
            this(classified, unknown, withoutStoredEvidence, Set.of(), List.of());
        }

        public Result(int classified, int unknown, int withoutStoredEvidence,
                      Set<String> withoutStoredEvidenceQids) {
            this(classified, unknown, withoutStoredEvidence, withoutStoredEvidenceQids,
                    List.of());
        }

        public Result {
            newlyClassified = List.copyOf(
                    newlyClassified == null ? List.of() : newlyClassified);
            withoutStoredEvidenceQids = withoutStoredEvidenceQids == null
                    ? Set.of() : Set.copyOf(withoutStoredEvidenceQids);
        }
    }

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
        EntityKindCandidates.Plan candidatePlan =
                EntityKindCandidates.compile(model, targetPool, rules);
        Set<String> kindClasses = rules.stream().map(EntityKindRule::className)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Map<String, Set<String>>> evidence =
                evidence(evidencePool, producers, candidatePlan.membersByRoleClass(),
                        kindClasses);
        LinkedHashMap<String, WikidataDynamicObject> candidates = new LinkedHashMap<>();
        candidatePlan.candidateQids().forEach(qid -> {
            WikidataDynamicObject value = candidatePlan.objectsByQid().get(qid);
            if (value != null) candidates.put(qid, value);
        });
        // The copies of ONE entity — never a part of it. A part carries its owner's
        // identifier, because that is how its fields load from the owner's QID, but it
        // is a different object: a birth name is not a person. Stamped as one, it lost
        // the production site its type key names — which IS a part's identity — and
        // owned composition, no longer able to find it, produced a second part for the
        // same owner on its next pass.
        Map<String, List<WikidataDynamicObject>> copiesByQid = new LinkedHashMap<>();
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(targetPool)) {
            if (object != null && !object.isPart() && WikidataIds.isQid(object.qid())) {
                copiesByQid.computeIfAbsent(object.qid(), ignored -> new ArrayList<>())
                        .add(object);
            }
        }

        int classified = 0, unknown = 0, withoutEvidence = 0;
        List<WikidataDynamicObject> newlyClassified = new ArrayList<>();
        Set<String> withoutEvidenceQids = new LinkedHashSet<>();
        for (WikidataDynamicObject candidate : candidates.values()) {
            Map<String, Set<String>> byPid = evidence.get(candidate.qid());
            boolean hasEvidence = false;
            boolean matched = false;
            boolean changed = false;
            for (EntityKindRule rule : rules) {
                if (!candidatePlan.eligible(candidate.qid(), rule.propertyPid())) continue;
                Set<String> values = byPid == null ? null : byPid.get(rule.propertyPid());
                if (values == null || values.isEmpty()) continue;
                hasEvidence = true;
                if (values.stream().anyMatch(rule.evidenceQids()::contains)) {
                    for (WikidataDynamicObject copy :
                            copiesByQid.getOrDefault(candidate.qid(), List.of())) {
                        if (!copy.directClassNames().contains(rule.className())) {
                            copy.assignClass(rule.className());
                            changed = true;
                        }
                    }
                    matched = true;
                }
            }
            if (matched) {
                for (WikidataDynamicObject copy :
                        copiesByQid.getOrDefault(candidate.qid(), List.of())) {
                    for (String legacyRole : RoleSelections.legacyRoleClassNames(model)) {
                        if (copy.directClassNames().contains(legacyRole)) changed = true;
                        copy.removeClass(legacyRole);
                    }
                    String carrier = carrier(copy.directClassNames(), model);
                    if (carrier != null) {
                        if (!carrier.equals(copy.typeName())
                                || !carrier.equals(copy.typeKey())) changed = true;
                        copy.type(carrier);
                        copy.typeKey(carrier);
                    }
                }
                if (changed) {
                    classified++;
                    // The copies of this entity that the pass restamped. Every copy of
                    // one qid is the same entity, so they go in together.
                    newlyClassified.addAll(
                            copiesByQid.getOrDefault(candidate.qid(), List.of()));
                }
            } else {
                unknown++;
                if (!hasEvidence) {
                    withoutEvidence++;
                    withoutEvidenceQids.add(candidate.qid());
                }
            }
        }
        if (log != null) {
            log.message("Snapshot entity-kind classification: " + classified
                    + " newly classified, " + unknown + " unknown (" + withoutEvidence
                    + " without stored evidence); " + candidates.size() + " of "
                    + candidatePlan.allRoleMembers()
                    + " role member(s) eligible from evidence producers.\n");
        }
        return new Result(classified, unknown, withoutEvidence, withoutEvidenceQids,
                List.copyOf(newlyClassified));
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
            Collection<WikidataDynamicObject> pool,
            Map<String, List<Producer>> producers,
            Map<String, Set<String>> membersByClass,
            Set<String> kindClasses) {
        Map<String, Map<String, Set<String>>> out = new LinkedHashMap<>();
        for (WikidataDynamicObject object : WikidataObjectGraph.reachable(pool)) {
            if (object == null || !WikidataIds.isQid(object.qid())) continue;
            for (Map.Entry<String, List<Producer>> byPid : producers.entrySet()) {
                for (Producer producer : byPid.getValue()) {
                    boolean owner = object.directClassNames().contains(producer.ownerClass());
                    boolean classifiedCopy = membersByClass.getOrDefault(
                                    producer.ownerClass(), Set.of()).contains(object.qid())
                            && object.directClassNames().stream().anyMatch(kindClasses::contains);
                    if (!owner && !classifiedCopy) {
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
