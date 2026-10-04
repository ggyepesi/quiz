package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityRepresentationRule;
import wikidata.explore.model.EntityRepresentations;
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
        boolean eligible(String qid, EntityRepresentations.Admission admission) {
            return admission != null && qidsByKindClass
                    .getOrDefault(admission.className(), Set.of()).contains(qid);
        }
    }

    private EntityKindCandidates() { }

    static Plan compile(GeneratedProjectModel model,
                        Collection<WikidataDynamicObject> pool,
                        Collection<EntityRepresentations.Admission> admissions) {
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

        Set<String> admittedClasses = admissions.stream()
                .map(EntityRepresentations.Admission::className)
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
        // A field that names an admitted kind directly — a predecessor typed Person — is
        // a population on which that admission is meaningful too. Its values become the
        // kind by evidence, as a role's do, never by the field's declaration: a qualifier
        // pointing at the office "Taoiseach" must not make it a person.
        for (Map.Entry<String, Set<String>> direct
                : directKindReferents(model, pool, admittedClasses).entrySet()) {
            byKind.computeIfAbsent(direct.getKey(), ignored -> new LinkedHashSet<>())
                    .addAll(direct.getValue());
            candidates.addAll(direct.getValue());
        }
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        byKind.forEach((name, qids) -> frozen.put(name, Set.copyOf(qids)));
        Map<String, Set<String>> frozenMembers = new LinkedHashMap<>();
        membersByRoleClass.forEach((name, qids) ->
                frozenMembers.put(name, Set.copyOf(qids)));
        return new Plan(Map.copyOf(frozen), Set.copyOf(candidates),
                Map.copyOf(frozenMembers), Map.copyOf(objectsByQid), all.size());
    }

    /** The QIDs held by every ENTITY field that targets an admitted kind, by that kind. */
    private static Map<String, Set<String>> directKindReferents(
            GeneratedProjectModel model, Collection<WikidataDynamicObject> pool,
            Set<String> admittedClasses) {
        Map<String, Map<String, String>> kindFields = new LinkedHashMap<>();
        for (wikidata.explore.model.GeneratedClassModel clazz : model.classes()) {
            if (clazz == null) continue;
            for (wikidata.explore.model.GeneratedFieldModel field : clazz.fields()) {
                if (field != null && field.type() == datasource.schema.FieldType.ENTITY
                        && admittedClasses.contains(field.entityClassName())) {
                    kindFields.computeIfAbsent(clazz.className(), ignored -> new LinkedHashMap<>())
                            .put(field.name(), field.entityClassName());
                }
            }
        }
        Map<String, Set<String>> byKind = new LinkedHashMap<>();
        if (kindFields.isEmpty()) return byKind;
        for (WikidataDynamicObject owner
                : wikidata.explore.extract.WikidataObjectGraph.reachable(pool)) {
            if (owner == null) continue;
            for (String className : owner.directClassNames()) {
                Map<String, String> fields = kindFields.get(className);
                if (fields == null) continue;
                fields.forEach((fieldName, kind) -> collectQids(owner.get(fieldName),
                        byKind.computeIfAbsent(kind, ignored -> new LinkedHashSet<>())));
            }
        }
        return byKind;
    }

    private static void collectQids(Object value, Set<String> into) {
        if (value instanceof WikidataDynamicObject object) {
            if (!object.isPart() && WikidataIds.isQid(object.qid())) into.add(object.qid());
        } else if (value instanceof Collection<?> values) {
            values.forEach(item -> collectQids(item, into));
        }
    }
}
