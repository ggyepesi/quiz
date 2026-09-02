package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.EntityRepresentationRule;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.RoleSelection;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the population on which each class admission is meaningful. Admission alone
 * never opts a role into a representation: an explicit {@link EntityRepresentationRule}
 * supplies the role population and names the admitted target class.
 */
final class EntityKindCandidates {
    record Plan(Map<String, Set<String>> qidsByKindClass,
                Set<String> candidateQids,
                Map<String, Set<String>> membersByRoleClass,
                Map<String, WikidataDynamicObject> objectsByQid,
                int allRoleMembers) {
        boolean eligible(String qid, EntityKindRule rule) {
            return rule != null && qidsByKindClass
                    .getOrDefault(rule.className(), Set.of()).contains(qid);
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
        // A directly stamped population is also a valid contextual source. The role
        // materialization above is still needed after a previous pass has replaced its
        // compatibility stamp: the owning field remains the durable source of membership.
        for (WikidataDynamicObject object : wikidata.explore.extract.WikidataObjectGraph
                .reachable(pool)) {
            if (object == null || object.isPart() || !WikidataIds.isQid(object.qid())) continue;
            objectsByQid.putIfAbsent(object.qid(), object);
            for (String className : object.directClassNames()) {
                membersByRoleClass.computeIfAbsent(
                        className, ignored -> new LinkedHashSet<>()).add(object.qid());
            }
        }

        Set<String> admittedClasses = rules.stream().map(EntityKindRule::className)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Set<String>> byKind = new LinkedHashMap<>();
        Set<String> candidates = new LinkedHashSet<>();
        for (EntityRepresentationRule representation : model.entityRepresentationRules()) {
            if (representation == null || !representation.isConfigured()
                    || !admittedClasses.contains(
                            representation.representationClassName())) continue;
            Set<String> eligible = byKind.computeIfAbsent(
                    representation.representationClassName(),
                    ignored -> new LinkedHashSet<>());
            eligible.addAll(membersByRoleClass.getOrDefault(
                    representation.roleClassName(), Set.of()));
            candidates.addAll(eligible);
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        byKind.forEach((name, qids) -> frozen.put(name, Set.copyOf(qids)));
        Map<String, Set<String>> frozenMembers = new LinkedHashMap<>();
        membersByRoleClass.forEach((name, qids) ->
                frozenMembers.put(name, Set.copyOf(qids)));
        return new Plan(Map.copyOf(frozen), Set.copyOf(candidates),
                Map.copyOf(frozenMembers), Map.copyOf(objectsByQid), all.size());
    }
}
