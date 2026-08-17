package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.model.RoleSelection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the population on which each entity-kind evidence property is meaningful.
 * A modeled producer such as {@code Nominee.type -> P31} scopes P31 kind rules to the
 * Nominee role. Without a referenced producer, the historical all-role behavior is
 * retained so a standalone kind rule remains valid.
 */
final class EntityKindCandidates {
    record Plan(Map<String, Set<String>> qidsByProperty,
                Set<String> candidateQids,
                Map<String, Set<String>> membersByRoleClass,
                Map<String, WikidataDynamicObject> objectsByQid,
                int allRoleMembers) {
        boolean eligible(String qid, String propertyPid) {
            return qidsByProperty.getOrDefault(propertyPid, Set.of()).contains(qid);
        }
    }

    private EntityKindCandidates() { }

    static Plan compile(GeneratedProjectModel model,
                        Collection<WikidataDynamicObject> pool,
                        Collection<EntityKindRule> rules) {
        Map<String, List<Viewable>> materialized = RoleSelections.materialize(model, pool);
        Map<String, Set<String>> membersByRoleClass = new LinkedHashMap<>();
        Map<String, WikidataDynamicObject> objectsByQid = new LinkedHashMap<>();
        Set<String> all = new LinkedHashSet<>();
        for (RoleSelection role : RoleSelections.definitions(model)) {
            for (Viewable value : materialized.getOrDefault(role.key(), List.of())) {
                if (!(value instanceof WikidataDynamicObject object)
                        || object.isPart() || !WikidataIds.isQid(object.qid())) continue;
                all.add(object.qid());
                objectsByQid.putIfAbsent(object.qid(), object);
                membersByRoleClass.computeIfAbsent(
                        role.name(), ignored -> new LinkedHashSet<>()).add(object.qid());
            }
        }

        Map<String, Set<String>> producerRolesByPid = new LinkedHashMap<>();
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null || MembershipPattern.of(owner, model)
                    != MembershipPattern.REFERENCED) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field == null || field.type() != FieldType.ENTITY) continue;
                String pid = field.mapping().propertyPid();
                if (WikidataIds.isPid(pid)) {
                    producerRolesByPid.computeIfAbsent(
                            pid, ignored -> new LinkedHashSet<>()).add(owner.className());
                }
            }
        }

        Map<String, Set<String>> byPid = new LinkedHashMap<>();
        Set<String> candidates = new LinkedHashSet<>();
        for (EntityKindRule rule : rules) {
            String pid = rule.propertyPid();
            Set<String> owners = producerRolesByPid.getOrDefault(pid, Set.of());
            Set<String> eligible = byPid.computeIfAbsent(pid, ignored -> new LinkedHashSet<>());
            if (owners.isEmpty()) {
                eligible.addAll(all); // compatibility for rules with no modeled producer
            } else {
                owners.forEach(owner -> eligible.addAll(
                        membersByRoleClass.getOrDefault(owner, Set.of())));
            }
            candidates.addAll(eligible);
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        byPid.forEach((pid, qids) -> frozen.put(pid, Set.copyOf(qids)));
        Map<String, Set<String>> frozenMembers = new LinkedHashMap<>();
        membersByRoleClass.forEach((name, qids) ->
                frozenMembers.put(name, Set.copyOf(qids)));
        return new Plan(Map.copyOf(frozen), Set.copyOf(candidates),
                Map.copyOf(frozenMembers), Map.copyOf(objectsByQid), all.size());
    }
}
