package wikidata.explore.transform;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Prunes ORPHAN entities from the served pool: a node that is UNTYPED (no domain
 * class stamp) AND referenced by nothing else in the pool. These are the labelled
 * leftovers of dropped work — e.g. a discovered reify subject whose nomination was
 * filtered out (self-referential phantom, disallowed value), so no served record
 * points at it and it never got a class. Unlike {@link DeadStubPrune} (which only
 * catches UNLABELLED stubs, displayName == qid), an orphan resolved a real label,
 * so it slips through — yet it is still dead weight: not a root, not referenced,
 * no fields.
 *
 * <p>Safe because a typed member (Nomination, Nominee, …) is kept regardless, and a
 * referenced entity is kept regardless — only untyped, unreferenced nodes go. One
 * pass: the orphans here carry no fields, so they reference nothing and can't be
 * keeping each other alive.
 */
public final class OrphanPrune {

    private OrphanPrune() {}

    /** @return the orphan set (identity-based), for the caller to drop from the pool. */
    public static Set<WikidataDynamicObject> apply(
            Collection<WikidataDynamicObject> pool, GenerationLog log) {

        Set<WikidataDynamicObject> orphans =
                Collections.newSetFromMap(new IdentityHashMap<>());
        if (pool == null) {
            return orphans;
        }

        // Every QID referenced as a field value anywhere in the pool.
        Set<String> referenced = new HashSet<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null) {
                for (Object v : o.dynamicFields().values()) {
                    collectRefs(v, referenced);
                }
            }
        }

        for (WikidataDynamicObject o : pool) {
            if (o != null && !o.hasTypeStamp()
                    && o.qid() != null && o.qid().matches("Q\\d+")
                    && !referenced.contains(o.qid())) {
                orphans.add(o);
            }
        }

        if (log != null && !orphans.isEmpty()) {
            log.message("Pruned " + orphans.size() + " orphan(s) — labelled but "
                    + "untyped and referenced by nothing (subjects of dropped "
                    + "nominations), not served.\n");
        }
        return orphans;
    }

    private static void collectRefs(Object value, Set<String> into) {
        if (value instanceof WikidataDynamicObject w && w.qid() != null) {
            into.add(w.qid());
        } else if (value instanceof Collection<?> col) {
            for (Object x : col) {
                collectRefs(x, into);
            }
        }
    }
}
