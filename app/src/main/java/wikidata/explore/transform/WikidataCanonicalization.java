package wikidata.explore.transform;

import canonical.Candidate;
import canonical.CanonicalInstance;
import canonical.CanonicalizationEngine;
import canonical.CanonicalizationPlan;
import canonical.KeyedReduction;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Attaches neutral canonical results to Wikidata's normalized object carriers.
 *
 * <p>The common engine owns key evaluation, partitions and field reduction. This
 * adapter owns the only source-specific question left: which already-normalized WDO
 * physically carries a partition's result. A preference may select (for example) the
 * work-anchored projection of a statement, but it cannot change membership or values.
 */
public final class WikidataCanonicalization {
    private WikidataCanonicalization() { }

    public record Result(
            List<WikidataDynamicObject> carriers,
            Map<WikidataDynamicObject, WikidataDynamicObject> canonicalByCandidate,
            Map<WikidataDynamicObject, String> keyByCandidate,
            Map<WikidataDynamicObject, List<String>> sourceIdentitiesByCandidate,
            KeyedReduction.Result reduction) {
        public Result {
            carriers = List.copyOf(carriers == null ? List.of() : carriers);
            canonicalByCandidate = immutableIdentityMap(canonicalByCandidate);
            keyByCandidate = immutableIdentityMap(keyByCandidate);
            sourceIdentitiesByCandidate = immutableIdentityMap(sourceIdentitiesByCandidate);
        }

        private static <V> Map<WikidataDynamicObject, V> immutableIdentityMap(
                Map<WikidataDynamicObject, V> source) {
            IdentityHashMap<WikidataDynamicObject, V> copy = new IdentityHashMap<>();
            if (source != null) copy.putAll(source);
            return java.util.Collections.unmodifiableMap(copy);
        }
    }

    public static Result apply(
            CanonicalizationPlan plan,
            List<WikidataDynamicObject> objects,
            Predicate<WikidataDynamicObject> preferred) {
        List<Candidate> candidates = new ArrayList<>();
        Map<Candidate, WikidataDynamicObject> objectByCandidate = new IdentityHashMap<>();
        for (WikidataDynamicObject object
                : objects == null ? List.<WikidataDynamicObject>of() : objects) {
            if (object == null) continue;
            Candidate candidate = WikidataCandidates.of(object);
            candidates.add(candidate);
            objectByCandidate.put(candidate, object);
        }

        KeyedReduction.Result reduced = CanonicalizationEngine.canonicalize(
                plan, candidates, WikidataCandidates.stableForm());
        Comparator<WikidataDynamicObject> stableCarrier = Comparator.comparing(
                value -> Objects.toString(value.getIdentifier(), ""));
        Predicate<WikidataDynamicObject> choose = preferred == null
                ? ignored -> true : preferred;
        List<WikidataDynamicObject> carriers = new ArrayList<>();
        Map<WikidataDynamicObject, WikidataDynamicObject> aliases = new IdentityHashMap<>();
        Map<WikidataDynamicObject, String> keys = new IdentityHashMap<>();
        Map<WikidataDynamicObject, List<String>> sourceIdentities = new IdentityHashMap<>();

        for (CanonicalInstance instance : reduced.instances()) {
            List<WikidataDynamicObject> partition = instance.candidates().stream()
                    .map(objectByCandidate::get).filter(Objects::nonNull).toList();
            if (partition.isEmpty()) continue;
            WikidataDynamicObject carrier = partition.stream().filter(choose)
                    .min(stableCarrier)
                    .orElseGet(() -> partition.stream().min(stableCarrier).orElseThrow());
            // A disagreement deliberately materializes NO value. The carrier is an
            // input candidate and may already hold one side, so writing only present
            // outputs would resurrect the arbitrary winner the engine rejected.
            plan.reductionByField().keySet().forEach(carrier::remove);
            instance.values().forEach(carrier::put);
            mergeSourceMetadata(partition, carrier);
            carriers.add(carrier);
            partition.forEach(candidate -> {
                aliases.put(candidate, carrier);
                keys.put(candidate, instance.key());
                sourceIdentities.put(candidate, instance.sourceIdentities());
            });
        }
        return new Result(carriers, aliases, keys, sourceIdentities, reduced);
    }

    /** Identity metadata is normalized source output too, even when it is not a modeled
     * field. Combining source candidates must not make aliases or category evidence
     * depend on which physical carrier was selected. */
    private static void mergeSourceMetadata(
            List<WikidataDynamicObject> partition, WikidataDynamicObject carrier) {
        java.util.SortedSet<String> aliases = new java.util.TreeSet<>();
        java.util.LinkedHashSet<datasource.evidence.CategoryMembership> categories =
                new java.util.LinkedHashSet<>();
        boolean categoriesAnswered = false;
        for (WikidataDynamicObject candidate : partition) {
            aliases.addAll(candidate.aliases());
            categories.addAll(candidate.categoryMemberships());
            categoriesAnswered |= candidate.categoryMembershipsAnswered();
        }
        carrier.aliases(aliases);
        if (categoriesAnswered) carrier.categoryMemberships(categories);
    }
}
