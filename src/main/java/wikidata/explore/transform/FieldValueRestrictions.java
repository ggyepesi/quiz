package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforces a field's {@code allowedQids} restriction on the extracted pool: a field
 * whose value(s) must come from a fixed QID set (e.g. the auto-injected {@code
 * target} field, restricted to the membership's Academy-Award categories) keeps only
 * values in that set. The query layer doesn't yet apply {@code allowedQids}, so a
 * generic relation like P1411 ("nominated for", shared by the Grammys) would
 * otherwise leak non-membership targets (e.g. Grammy categories) into the field. A
 * cheap in-memory prune after extraction; the values are already loaded.
 */
public final class FieldValueRestrictions {

    private FieldValueRestrictions() {}

    public static void apply(GeneratedProjectModel project,
                             Collection<WikidataDynamicObject> pool) {
        if (project == null || pool == null) {
            return;
        }
        for (GeneratedClassModel clazz : project.classes()) {
            for (GeneratedFieldModel f : clazz.fields()) {
                Set<String> allowed = clean(f.mapping().allowedQids());
                if (allowed.isEmpty()) {
                    continue;
                }
                for (WikidataDynamicObject o : pool) {
                    if (o != null && clazz.className().equals(o.typeName())) {
                        prune(o, f.name(), allowed);
                    }
                }
            }
        }
    }

    private static void prune(WikidataDynamicObject o, String field, Set<String> allowed) {
        Object v = o.get(field);
        if (v instanceof Collection<?> col) {
            List<Object> kept = new ArrayList<>();
            for (Object item : col) {
                if (allowedValue(item, allowed)) {
                    kept.add(item);
                }
            }
            if (kept.isEmpty()) {
                o.remove(field);
            } else if (kept.size() == 1) {
                o.put(field, kept.get(0));
            } else {
                o.put(field, kept);
            }
        } else if (v != null && !allowedValue(v, allowed)) {
            o.remove(field);
        }
    }

    private static boolean allowedValue(Object v, Set<String> allowed) {
        // Only entity-valued items carry a QID; non-entity values aren't restricted.
        return !(v instanceof WikidataDynamicObject w) || allowed.contains(w.qid());
    }

    private static Set<String> clean(Set<String> qids) {
        Set<String> out = new LinkedHashSet<>();
        if (qids != null) {
            for (String q : qids) {
                if (q == null) {
                    continue;
                }
                q = q.trim();
                int slash = q.lastIndexOf('/');
                if (slash >= 0) {
                    q = q.substring(slash + 1);
                }
                if (q.matches("Q\\d+")) {
                    out.add(q);
                }
            }
        }
        return out;
    }
}
