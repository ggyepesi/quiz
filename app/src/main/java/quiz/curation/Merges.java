package quiz.curation;

import objectview.ViewableAdapter;
import objectview.field.FieldAccess;
import objectview.field.FieldRef;
import objectview.field.FieldSet;
import quiz.Quizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link Merge} directives to a loaded pool: folds each duplicate's field
 * values into its primary and removes the duplicate. An overlay (not a snapshot
 * mutation), so it re-applies safely after a regeneration — the same spirit as
 * {@link Corrections}. Reference redirection (other entities still pointing at the
 * removed duplicate) is a follow-up; for now the duplicate simply leaves the pool.
 */
public final class Merges {

    private Merges() {}

    /** Overlay {@code merges} onto {@code pool}; returns how many merges were applied. */
    public static int apply(Collection<? extends Quizable> pool, List<Merge> merges) {
        if (pool == null || merges == null || merges.isEmpty()) {
            return 0;
        }
        Map<String, Quizable> byId = new HashMap<>();
        for (Quizable q : pool) {
            if (q != null && q.getIdentifier() != null) {
                byId.putIfAbsent(q.getIdentifier(), q);
            }
        }

        int merged = 0;
        for (Merge m : merges) {
            if (m == null || m.primary() == null || m.duplicate() == null
                    || m.primary().equals(m.duplicate())) {
                continue;
            }
            Quizable primary = byId.get(m.primary());
            Quizable duplicate = byId.get(m.duplicate());
            if (primary == null || duplicate == null || primary == duplicate) {
                continue;
            }
            union(primary, duplicate);
            pool.remove(duplicate);
            merged++;
        }
        return merged;
    }

    /** Fold the duplicate's field values into the primary: fill a field the primary
     *  lacks, union collections/maps, keep the primary's scalar on a conflict (the
     *  primary is the surviving identity). */
    private static void union(Quizable primary, Quizable duplicate) {
        for (FieldRef ref : FieldSet.of(duplicate).fields()) {
            String name = ref.name();
            Object dv = FieldAccess.getPath(duplicate, name);
            if (!ViewableAdapter.isValidQuizValue(dv)) {
                continue;   // duplicate has nothing to contribute here
            }
            Object pv = FieldAccess.getPath(primary, name);
            if (!ViewableAdapter.isValidQuizValue(pv)) {
                FieldAccess.setPath(primary, name, dv);          // primary was empty
            } else if (pv instanceof Collection<?> pc && dv instanceof Collection<?> dc) {
                List<Object> union = new ArrayList<>(pc);
                for (Object item : dc) {
                    if (!union.contains(item)) {
                        union.add(item);
                    }
                }
                FieldAccess.setPath(primary, name, union);
            } else if (pv instanceof Map<?, ?> pm && dv instanceof Map<?, ?> dm) {
                Map<Object, Object> union = new LinkedHashMap<>(pm);
                for (Map.Entry<?, ?> e : dm.entrySet()) {
                    union.putIfAbsent(e.getKey(), e.getValue());
                }
                FieldAccess.setPath(primary, name, union);
            }
            // else: primary already holds a scalar — the primary wins.
        }
    }
}
