package domain.build;

import objectview.Viewable;
import domain.provenance.SourceIdentities;
import domain.provenance.WikidataRelationRouteMatch;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataObjectGraph;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Settles retained/not-retained route matches against the finalized owner fields. */
public final class WikidataRelationRouteCoverage {
    private WikidataRelationRouteCoverage() { }

    public record Summary(
            String ownerClass,
            String field,
            String routeId,
            String route,
            int matchedOwners,
            int retainedOwners,
            int matches,
            int retainedMatches) { }

    public static List<Summary> settle(Collection<WikidataDynamicObject> roots) {
        if (roots == null || roots.isEmpty()) return List.of();
        Map<Key, Counts> counts = new LinkedHashMap<>();
        for (WikidataDynamicObject owner : WikidataObjectGraph.reachable(roots)) {
            List<WikidataRelationRouteMatch> settled = new ArrayList<>();
            for (WikidataRelationRouteMatch match : owner.wikidataRelationRouteMatches()) {
                boolean retained = containsQid(owner.get(match.field()),
                        match.terminal().qid());
                WikidataRelationRouteMatch value = match.settled(retained);
                settled.add(value);
                Key key = new Key(match.ownerClass(), match.field(), match.routeId(),
                        match.route());
                Counts result = counts.computeIfAbsent(key, ignored -> new Counts());
                String ownerIdentity = owner.typeKey() + "\u0000" + owner.getIdentifier();
                result.matchedOwners.add(ownerIdentity);
                String matchIdentity = ownerIdentity + "\u0000" + match.getIdentifier();
                result.matchedMatches.add(matchIdentity);
                if (retained) {
                    result.retainedOwners.add(ownerIdentity);
                    result.retainedMatches.add(matchIdentity);
                }
            }
            owner.wikidataRelationRouteMatches(settled);
        }
        return counts.entrySet().stream().map(entry -> new Summary(
                entry.getKey().ownerClass(), entry.getKey().field(),
                entry.getKey().routeId(), entry.getKey().route(),
                entry.getValue().matchedOwners.size(),
                entry.getValue().retainedOwners.size(),
                entry.getValue().matchedMatches.size(),
                entry.getValue().retainedMatches.size())).toList();
    }

    static boolean containsQid(Object value, String qid) {
        if (value == null || qid == null || qid.isBlank()) return false;
        if (value instanceof Viewable viewable) {
            return qid.equals(SourceIdentities.wikidataQid(viewable));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> containsQid(item, qid));
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(item -> containsQid(item, qid));
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                if (containsQid(Array.get(value, index), qid)) return true;
            }
        }
        return false;
    }

    private record Key(String ownerClass, String field, String routeId, String route) { }

    private static final class Counts {
        private final LinkedHashSet<String> matchedOwners = new LinkedHashSet<>();
        private final LinkedHashSet<String> retainedOwners = new LinkedHashSet<>();
        private final LinkedHashSet<String> matchedMatches = new LinkedHashSet<>();
        private final LinkedHashSet<String> retainedMatches = new LinkedHashSet<>();
    }
}
