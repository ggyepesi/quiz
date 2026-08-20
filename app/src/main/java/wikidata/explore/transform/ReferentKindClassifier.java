package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.generation.FactDemand;
import wikidata.explore.generation.FactDemandBinder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assigns genuine modeled kinds to role-selection members from configured evidence. */
public final class ReferentKindClassifier {
    private ReferentKindClassifier() {}

    public static Result apply(GeneratedProjectModel model,
                               Collection<WikidataDynamicObject> pool,
                               WikidataApiClient api, GenerationLog log) {
        return apply(model, pool, api, log, null);
    }

    /** Fetches evidence only for the requested candidates. A generation first uses
     * already-loaded modeled fields; this fallback covers entities for which the
     * snapshot had no evidence, without downloading the same P31 claims twice. */
    public static Result apply(GeneratedProjectModel model,
                               Collection<WikidataDynamicObject> pool,
                               WikidataApiClient api, GenerationLog log,
                               Set<String> candidateQids) {
        if (model == null || pool == null || api == null) return new Result(0, 0, 0);
        List<EntityKindRule> rules = model.entityKindRules().stream()
                .filter(EntityKindRule::isConfigured)
                .filter(rule -> model.findClass(rule.className()) != null).toList();
        if (rules.isEmpty()) return new Result(0, 0, 0);

        EntityKindCandidates.Plan candidatePlan =
                EntityKindCandidates.compile(model, pool, rules);
        LinkedHashMap<String, WikidataDynamicObject> candidates = new LinkedHashMap<>();
        candidatePlan.candidateQids().stream()
                .filter(qid -> candidateQids == null || candidateQids.contains(qid))
                .forEach(qid -> {
                    WikidataDynamicObject value = candidatePlan.objectsByQid().get(qid);
                    if (value != null) candidates.put(qid, value);
                });
        if (candidates.isEmpty()) return new Result(0, 0, 0);

        Set<String> properties = new LinkedHashSet<>();
        rules.forEach(rule -> properties.add(rule.propertyPid()));
        // Bank what a classified entity will be asked for next. wbgetentities returns
        // the whole document either way, so this costs nothing to fetch and saves the
        // fetch that would otherwise follow classification — for a model that does not
        // declare its evidence property as a field, this is the ONLY place the closure
        // can be banked, because no field load asks about these entities first.
        int evidencePids = properties.size();
        for (String pid : properties) {
            Set<String> eligible = new LinkedHashSet<>(
                    candidatePlan.qidsByProperty().getOrDefault(pid, Set.of()));
            eligible.retainAll(candidates.keySet());
            api.facts().recordDemand("entity-kind evidence", eligible, List.of(pid));
        }
        properties.addAll(ReferentFieldLoad.kindPropertyClosure(model,
                rules.stream().map(EntityKindRule::className).toList()));
        FactDemandBinder.bind(FactDemand.of(
                        "entity-kind evidence", "kind candidates", properties,
                        "retain evidence and possible classified-kind closure"),
                candidates.keySet(), api.facts(), "kind classification");
        WikidataApiClient.PartialEntities partial;
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        try (GenerationLog.Group group = sink.group(
                "Classify " + candidates.size() + " role member(s) from "
                        + evidencePids + " evidence property(ies); retain "
                        + properties.size() + " planned property slice(s) ("
                        + String.join(", ", properties) + ")")) {
            partial = api.getEntityClaimsPartial(new ArrayList<>(candidates.keySet()),
                    new ArrayList<>(properties), group.batchSink());
        } catch (Exception failure) {
            if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
            else if (log != null) log.message("Entity kind classification failed ("
                    + failure.getMessage() + ")\n");
            // WHICH entities went unchecked, not just how many: the run reports its
            // unresolved work by QID, and a bare count is dropped on the way there — so
            // a call that failed outright would leave the run claiming it classified
            // everything it could.
            return new Result(0, 0, candidates.size(),
                    List.copyOf(candidates.keySet()));
        }
        Map<String, WikidataApiClient.ApiEntity> evidence = partial.entities();

        int classified = 0;
        int unknown = 0;
        int unavailable = 0;
        for (WikidataDynamicObject candidate : candidates.values()) {
            WikidataApiClient.ApiEntity entity = evidence.get(candidate.qid());
            if (entity == null) {
                unavailable++;
                continue; // retain the compatibility role stamp; evidence was unavailable
            }
            boolean matched = false;
            boolean changed = false;
            for (EntityKindRule rule : rules) {
                if (!candidatePlan.eligible(candidate.qid(), rule.propertyPid())) continue;
                if (entity != null && entity.entityQids(rule.propertyPid()).stream()
                        .anyMatch(rule.evidenceQids()::contains)) {
                    if (!candidate.directClassNames().contains(rule.className())) {
                        candidate.assignClass(rule.className());
                        changed = true;
                    }
                    matched = true;
                }
            }
            if (matched) {
                // Retract the compatibility role stamp only after a genuine kind keeps
                // the entity typed. An unknown thin referent must remain an object:
                // without a stamp BareReferenceCollapse can turn it into a String on
                // reload, losing canonical identity and role-selection membership.
                for (String legacyRole : RoleSelections.legacyRoleClassNames(model)) {
                    if (candidate.directClassNames().contains(legacyRole)) changed = true;
                    candidate.removeClass(legacyRole);
                }
                String carrier = carrier(candidate.directClassNames(), model);
                if (carrier != null) {
                    if (!carrier.equals(candidate.typeName())
                            || !carrier.equals(candidate.typeKey())) changed = true;
                    candidate.type(carrier);
                    candidate.typeKey(carrier);
                }
                if (changed) classified++;
            } else {
                unknown++;
            }
        }
        if (partial.failedBatches() > 0 && log != null) {
            log.message("WARNING: entity kind evidence unavailable for " + unavailable
                    + " entity(ies) in " + partial.failedBatches()
                    + " exhausted API batch(es); their role classes were retained: "
                    + summarizeQids(partial.unavailableQids()) + "\n");
        }
        return new Result(classified, unknown, unavailable, partial.unavailableQids());
    }

    /** Same carrier rule as the domain/store: deepest modeled class, then name. */
    private static String carrier(Set<String> classes, GeneratedProjectModel model) {
        String best = null;
        int bestDepth = -1;
        for (String candidate : classes) {
            int depth = 0;
            Set<String> seen = new java.util.HashSet<>();
            String current = candidate;
            while (current != null && seen.add(current)) {
                depth++;
                var cls = model.findClass(current);
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

    public record Result(
            int classified, int unknown, int unavailable, List<String> unavailableQids) {
        public Result(int classified, int unknown, int unavailable) {
            this(classified, unknown, unavailable, List.of());
        }

        public Result {
            unavailableQids = unavailableQids == null
                    ? List.of() : List.copyOf(unavailableQids);
        }
    }

    private static String summarizeQids(List<String> qids) {
        if (qids == null || qids.isEmpty()) return "(none listed)";
        int shown = Math.min(10, qids.size());
        String head = String.join(", ", qids.subList(0, shown));
        return qids.size() == shown ? head : head + " … and "
                + (qids.size() - shown) + " more";
    }
}
