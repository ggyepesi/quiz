package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drops referents that are Wikimedia-INTERNAL non-entities — a disambiguation page,
 * a duplicated-item page, a category/list page — which are never real domain members
 * but occasionally slip in as a wrong statement/qualifier value (e.g. a nomination's
 * P805 pointing at the "1968 Academy Awards" DISAMBIGUATION page instead of the real
 * ceremony edition). Left in, such a referent inflates a class's count and renders as
 * an attribute-less phantom card.
 *
 * <p>The referencing RECORD is kept: only the bad referent is removed and inbound
 * references to it are scrubbed (a single-valued field is cleared, a collection loses
 * just that member). So the nomination survives — it merely loses its wrong ceremony
 * link — rather than being dropped over one bad qualifier.
 *
 * <p>Scoped for cost: only entities STAMPED as a modeled class are vetted (a wrong
 * reference always lands as one of those; raw type/genre value entities are skipped),
 * and when a class already carries its own P31 as a declared field (e.g. Nominee.type)
 * that is read off the instance instead of re-fetched. So the only network cost is a
 * P31 fetch for the stamped members whose P31 we don't already have (e.g. Ceremony,
 * ForWork). Walks the reachable graph, since such a referent can be nested-only.
 */
public final class DisambiguationPrune {

    /** Wikimedia-internal "non-entity" types — never a valid domain member. */
    private static final Set<String> INTERNAL_TYPES = Set.of(
            "Q4167410",    // Wikimedia disambiguation page
            "Q22808320",   // Wikimedia human name disambiguation page
            "Q17362920",   // Wikimedia duplicated page
            "Q4167836",    // Wikimedia category
            "Q13406463");  // Wikimedia list article

    private DisambiguationPrune() {}

    /** @return the pruned top-level objects — the caller removes them from the served
     *  pool (nested ones simply become unreachable once references are scrubbed). */
    public static Set<WikidataDynamicObject> apply(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> pool,
            WikidataApiClient api,
            GenerationLog log) {

        Set<WikidataDynamicObject> removed =
                Collections.newSetFromMap(new IdentityHashMap<>());
        if (pool == null || api == null) {
            return removed;
        }
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;

        // Modeled class names (the stamps we vet) and, per class, the declared field
        // that already holds its P31 (so we needn't fetch it).
        Set<String> modeled = new HashSet<>();
        Map<String, String> p31FieldByClass = new HashMap<>();
        if (model != null) {
            for (GeneratedClassModel c : model.classes()) {
                if (c == null) {
                    continue;
                }
                modeled.add(c.className());
                for (GeneratedFieldModel f : c.fields()) {
                    if (f != null && f.type() == FieldType.ENTITY
                            && "P31".equals(clean(f.mapping().propertyPid()))) {
                        p31FieldByClass.put(c.className(), f.name());
                        break;
                    }
                }
            }
        }

        Set<String> badQids = new HashSet<>();
        LinkedHashSet<String> needFetch = new LinkedHashSet<>();
        List<WikidataDynamicObject> reachable = collectReachable(pool);
        for (WikidataDynamicObject o : reachable) {
            String qid = o.qid();
            if (qid == null || !WikidataIds.isQid(qid)) {
                continue;
            }
            // Only vet STAMPED domain members; a wrong reference becomes one. Raw
            // value entities (type/genre tags) are unstamped and skipped.
            if (o.typeName() == null || !modeled.contains(o.typeName())) {
                continue;
            }
            String p31Field = p31FieldByClass.get(o.typeName());
            Object p31Value = p31Field == null ? null : o.get(p31Field);
            if (p31Value != null) {
                if (hasInternalType(p31Value)) {   // P31 already on the instance
                    badQids.add(qid);
                }
            } else {
                needFetch.add(qid);
            }
        }

        if (!needFetch.isEmpty()) {
            Map<String, WikidataApiClient.ApiEntity> details;
            try {
                details = api.getEntities(
                        new ArrayList<>(needFetch), List.of("P31"), sink::subquery);
            } catch (Exception ex) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                } else {
                    sink.message("Disambiguation prune P31 fetch failed ("
                            + ex.getMessage() + ")\n");
                }
                details = Map.of();
            }
            for (String q : needFetch) {
                WikidataApiClient.ApiEntity e = details.get(q);
                if (e == null) {
                    continue;
                }
                for (String t : e.claim("P31")) {
                    if (INTERNAL_TYPES.contains(t)) {
                        badQids.add(q);
                        break;
                    }
                }
            }
        }

        if (badQids.isEmpty()) {
            return removed;
        }

        for (WikidataDynamicObject o : reachable) {
            if (o.qid() != null && badQids.contains(o.qid())) {
                removed.add(o);
            } else {
                scrubReferences(o, badQids);
            }
        }
        sink.message("Disambiguation prune: removed " + removed.size()
                + " Wikimedia-internal referent(s) " + new ArrayList<>(badQids)
                + ", references scrubbed (referencing records kept)\n");
        return removed;
    }

    /** True if any value member is a WDO whose QID is a Wikimedia-internal type. */
    private static boolean hasInternalType(Object p31Value) {
        if (p31Value instanceof WikidataDynamicObject w) {
            return w.qid() != null && INTERNAL_TYPES.contains(w.qid());
        }
        if (p31Value instanceof Collection<?> c) {
            for (Object item : c) {
                if (hasInternalType(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Clears field values that point at a bad QID: a single-valued field is removed
     *  outright; a collection loses just those members (and is removed if it empties). */
    private static void scrubReferences(WikidataDynamicObject o, Set<String> badQids) {
        List<String> clear = new ArrayList<>();
        Map<String, List<Object>> shrink = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : o.dynamicFieldValues().entrySet()) {
            Object v = e.getValue();
            if (isBad(v, badQids)) {
                clear.add(e.getKey());
            } else if (v instanceof Collection<?> c) {
                List<Object> kept = new ArrayList<>();
                boolean changed = false;
                for (Object item : c) {
                    if (isBad(item, badQids)) {
                        changed = true;
                    } else {
                        kept.add(item);
                    }
                }
                if (changed) {
                    shrink.put(e.getKey(), kept);
                }
            }
        }
        for (String f : clear) {
            o.remove(f);
        }
        for (Map.Entry<String, List<Object>> e : shrink.entrySet()) {
            if (e.getValue().isEmpty()) {
                o.remove(e.getKey());
            } else {
                o.put(e.getKey(), e.getValue());
            }
        }
    }

    private static boolean isBad(Object v, Set<String> badQids) {
        return v instanceof WikidataDynamicObject w
                && w.qid() != null && badQids.contains(w.qid());
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static List<WikidataDynamicObject> collectReachable(
            Collection<WikidataDynamicObject> roots) {
        Set<WikidataDynamicObject> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<WikidataDynamicObject> queue = new ArrayDeque<>();
        for (WikidataDynamicObject r : roots) {
            if (r != null && seen.add(r)) {
                queue.addLast(r);
            }
        }
        List<WikidataDynamicObject> out = new ArrayList<>(seen.size());
        while (!queue.isEmpty()) {
            WikidataDynamicObject o = queue.pollFirst();
            out.add(o);
            for (Object v : o.dynamicFieldValues().values()) {
                push(v, seen, queue);
            }
        }
        return out;
    }

    private static void push(
            Object v, Set<WikidataDynamicObject> seen,
            Deque<WikidataDynamicObject> queue) {
        if (v instanceof WikidataDynamicObject w) {
            if (seen.add(w)) {
                queue.addLast(w);
            }
        } else if (v instanceof Collection<?> c) {
            for (Object item : c) {
                push(item, seen, queue);
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                push(item, seen, queue);
            }
        }
    }
}
