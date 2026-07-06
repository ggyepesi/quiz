package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses BARE references to plain display-name strings, in place. A bare
 * reference is an unstamped {@link WikidataDynamicObject} carrying no substance
 * (no fields, or only the auto-seeded {@code wikidata} link) — e.g. the {@code
 * type} values ("film", "song"): it isn't an entity the domain models, just a
 * label, so a field holding it reads as a String ("film") rather than a
 * WikidataDynamicObject chip. Stamped or fielded references are left alone.
 *
 * <p>Run on a freshly loaded pool (the caller owns the copy); typed member
 * objects themselves are never collapsed, only their field VALUES.
 */
public final class BareReferenceCollapse {

    private BareReferenceCollapse() {}

    public static void apply(Collection<WikidataDynamicObject> pool) {
        for (WikidataDynamicObject o : pool) {
            if (o == null) {
                continue;
            }
            for (Map.Entry<String, Object> e : o.dynamicFields().entrySet()) {
                e.setValue(collapse(e.getValue()));
            }
        }
    }

    private static Object collapse(Object v) {
        if (v instanceof WikidataDynamicObject w && isBare(w)) {
            return w.getDisplayName();
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(collapse(item));
            }
            return out;
        }
        return v;
    }

    private static boolean isBare(WikidataDynamicObject w) {
        if (w.hasTypeStamp()) {
            return false;
        }
        Set<String> fields = w.dynamicFields().keySet();
        return fields.isEmpty()
                || (fields.size() == 1 && fields.contains("wikidata"));
    }
}
