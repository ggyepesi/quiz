package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.Collections;
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

        // The exact objects referenced as field values anywhere in the pool. A QID is
        // not enough: Person/Q71231 and an obsolete untyped Q71231 shell are different
        // carriers. A reference to the former must not keep the latter alive.
        Set<WikidataDynamicObject> referenced =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (WikidataDynamicObject o : pool) {
            if (o != null) {
                for (Object v : o.dynamicFields().values()) {
                    collectRefs(v, referenced);
                }
            }
        }

        for (WikidataDynamicObject o : pool) {
            if (o != null && !o.hasTypeStamp()
                    && o.qid() != null && WikidataIds.isQid(o.qid())
                    && !referenced.contains(o)) {
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

    private static void collectRefs(Object value, Set<WikidataDynamicObject> into) {
        if (value instanceof WikidataDynamicObject w) {
            into.add(w);
        } else if (value instanceof Collection<?> col) {
            for (Object x : col) {
                collectRefs(x, into);
            }
        }
    }
}
