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
 * Applies {@link Merge} directives to a loaded pool: folds each duplicate's field values
 * into its primary (per the merge's approved per-field resolution) and removes the
 * duplicate. An overlay (not a snapshot mutation), so it re-applies safely after a
 * regeneration — the same spirit as {@link Corrections}. Reference redirection (other
 * entities still pointing at the removed duplicate) is a follow-up.
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
            union(primary, duplicate, m);
            pool.remove(duplicate);
            merged++;
        }
        return merged;
    }

    /** Fold the duplicate's field values into the primary, honoring the merge's per-field
     *  source (PRIMARY/DUPLICATE/BOTH); a field with no explicit source uses the default
     *  (fill an empty primary, union collections/maps, else keep the primary's scalar). */
    private static void union(Quizable primary, Quizable duplicate, Merge merge) {
        for (FieldRef ref : FieldSet.of(duplicate).fields()) {
            String name = ref.name();
            Object dv = FieldAccess.getPath(duplicate, name);
            Object pv = FieldAccess.getPath(primary, name);
            String src = merge.sourceFor(name);

            if (Merge.PRIMARY.equals(src)) {
                continue;                                    // keep the primary as-is
            }
            if (Merge.DUPLICATE.equals(src)) {
                if (ViewableAdapter.isValidQuizValue(dv)) {
                    FieldAccess.setPath(primary, name, dv);
                }
                continue;
            }
            if (Merge.BOTH.equals(src)) {
                FieldAccess.setPath(primary, name, unionValue(pv, dv));
                continue;
            }

            // Default resolution.
            if (!ViewableAdapter.isValidQuizValue(dv)) {
                continue;                                    // nothing to contribute
            }
            if (!ViewableAdapter.isValidQuizValue(pv)) {
                FieldAccess.setPath(primary, name, dv);      // primary was empty
            } else if ((pv instanceof Collection<?> && dv instanceof Collection<?>)
                    || (pv instanceof Map<?, ?> && dv instanceof Map<?, ?>)) {
                FieldAccess.setPath(primary, name, unionValue(pv, dv));
            }
            // else: primary already holds a scalar — the primary wins.
        }
    }

    /** The union of two values: concatenated distinct list, merged map (primary keys
     *  win), or — for scalars — the primary if present, else the duplicate. */
    public static Object unionValue(Object pv, Object dv) {
        if (pv instanceof Collection<?> pc && dv instanceof Collection<?> dc) {
            List<Object> union = new ArrayList<>(pc);
            for (Object item : dc) {
                if (!union.contains(item)) {
                    union.add(item);
                }
            }
            return union;
        }
        if (pv instanceof Map<?, ?> pm && dv instanceof Map<?, ?> dm) {
            Map<Object, Object> union = new LinkedHashMap<>(pm);
            for (Map.Entry<?, ?> e : dm.entrySet()) {
                union.putIfAbsent(e.getKey(), e.getValue());
            }
            return union;
        }
        return ViewableAdapter.isValidQuizValue(pv) ? pv : dv;
    }
}
