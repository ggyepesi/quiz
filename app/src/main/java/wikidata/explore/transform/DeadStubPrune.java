package wikidata.explore.transform;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Prunes DEAD STUB entities from a pool: a node whose label never resolved
 * (getDisplayName falls back to the raw {@code qid}) AND that carries no fields —
 * i.e. a QID some statement still points at whose Wikidata page is gone
 * (deleted/merged/redirected, "no such page"). Left in, such a node surfaces as a
 * ghost value — e.g. an unnamed {@code type} Q61283808 that isn't a real page and
 * inflates the distinct-type count at generation while the snapshot (deduped,
 * reachability-based) drops it, so generation and load disagree.
 *
 * <p>Removes the stubs from consideration AND unlinks every reference to them, so
 * no surviving object still points at one (a nominee's {@code type} collection
 * loses the dead ref) and the mapper never materializes a phantom typed instance.
 * Returns the pruned set so the caller can also drop them from roots / the served
 * pool. A bare reference that DID resolve a label (label != qid) is kept — it
 * still renders as a usable link.
 */
public final class DeadStubPrune {

    private DeadStubPrune() {}

    public static Set<WikidataDynamicObject> apply(
            Collection<WikidataDynamicObject> pool, GenerationLog log) {

        Set<WikidataDynamicObject> dead =
                Collections.newSetFromMap(new IdentityHashMap<>());
        if (pool == null) {
            return dead;
        }
        for (WikidataDynamicObject o : pool) {
            if (isDeadStub(o)) {
                dead.add(o);
            }
        }
        if (dead.isEmpty()) {
            return dead;
        }

        for (WikidataDynamicObject o : pool) {
            if (dead.contains(o)) {
                continue;
            }
            for (String field : new ArrayList<>(o.dynamicFields().keySet())) {
                Object v = o.get(field);
                if (v instanceof Collection<?> col) {
                    List<Object> kept = new ArrayList<>();
                    boolean changed = false;
                    for (Object x : col) {
                        if (x instanceof WikidataDynamicObject w && dead.contains(w)) {
                            changed = true;
                        } else {
                            kept.add(x);
                        }
                    }
                    if (changed) {
                        o.put(field, kept);
                    }
                } else if (v instanceof WikidataDynamicObject w && dead.contains(w)) {
                    o.remove(field);
                }
            }
        }
        if (log != null) {
            log.message("Pruned " + dead.size()
                    + " dead stub(s) (unresolved QID, no page)\n");
        }
        return dead;
    }

    /** A node with a real qid, no fields, and no resolved label (getDisplayName
     *  returns the qid). */
    static boolean isDeadStub(WikidataDynamicObject o) {
        if (o == null) {
            return false;
        }
        String qid = o.qid();
        if (qid == null || qid.isBlank()) {
            return false;
        }
        return o.dynamicFields().isEmpty() && qid.equals(o.getDisplayName());
    }
}
