package quiz.transform.app;

import objectview.field.DynamicFields;
import objectview.media.ImagePane;
import objectview.utils.swing.CachedImage;
import quiz.Quizable;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Quizable graph to a {@link WikidataDynamicObject} pool so a transform
 * result can be SAVED as a snapshot (the store is WDO-based). A WDO passes through;
 * a {@link DynamicFields} object (e.g. a PROJECT-derived DynamicQuizable) copies its
 * property map; a hand-written Quizable (State, NobelPrize, …) is materialized by
 * reflection. Quizable-valued fields are converted recursively (deduped by
 * identity, cycle-safe). The wikidata bridge for the transform layer.
 */
public final class QuizableToWdo {

    private QuizableToWdo() {}

    public static List<WikidataDynamicObject> pool(Collection<? extends Quizable> members) {
        Map<Object, WikidataDynamicObject> seen = new IdentityHashMap<>();
        List<WikidataDynamicObject> roots = new ArrayList<>();
        for (Quizable m : members) {
            Object c = convert(m, seen);
            if (c instanceof WikidataDynamicObject w) {
                roots.add(w);
            }
        }
        requireIdentities(seen.values());
        return roots;
    }

    /** Fail loud if any converted entity has a BLANK identity. The snapshot store keys
     *  by qid and SILENTLY DROPS blank-qid entities (their refs then serialize as null),
     *  so a blank identity is data loss, not a cosmetic issue — surface it at save
     *  instead of shipping a broken snapshot. */
    private static void requireIdentities(Collection<WikidataDynamicObject> entities) {
        Map<String, Integer> blankByType = new LinkedHashMap<>();
        for (WikidataDynamicObject w : entities) {
            if (w.isValueObject()) {
                continue;   // value objects are inlined — a blank id is expected/correct
            }
            if (w.qid() == null || w.qid().isBlank()) {
                blankByType.merge(
                        w.typeName() == null || w.typeName().isBlank() ? "?" : w.typeName(),
                        1, Integer::sum);
            }
        }
        if (!blankByType.isEmpty()) {
            int total = blankByType.values().stream().mapToInt(Integer::intValue).sum();
            throw new IllegalStateException(
                    total + " entity(ies) have a BLANK identity and would be silently "
                    + "dropped from the snapshot (blank getIdentifier() -> no qid -> "
                    + "nested refs become null). Give them a non-blank identity. "
                    + "By type: " + blankByType);
        }
    }

    private static Object convert(Object v, Map<Object, WikidataDynamicObject> seen) {
        if (v == null) {
            return null;
        }
        if (v instanceof WikidataDynamicObject w) {
            return w;
        }
        if (v instanceof Quizable q) {
            WikidataDynamicObject cached = seen.get(q);
            if (cached != null) {
                return cached;
            }
            // A VALUE object is inlined, not pooled — it needs no identity (its display
            // name is just a label). Everything else is an entity, keyed by identity.
            boolean value = q instanceof quiz.ValueObject;
            String id = value ? null : q.getIdentifier();
            if (!value && (id == null || id.isBlank())) {
                id = q.getDisplayName();
            }
            WikidataDynamicObject o = new WikidataDynamicObject(id, q.getDisplayName());
            o.type(q.typeName());
            o.valueObject(value);
            seen.put(q, o);
            // Copy every field, whichever representation — no instanceof branch.
            objectview.field.FieldSet set = objectview.field.FieldSet.of(q);
            for (objectview.field.FieldRef ref : set.fields()) {
                Object cv = convert(set.read(ref.name()), seen);
                // Skip null AND blank scalars — a blank value carries no information and
                // would otherwise render as an empty field row (e.g. flagStatus="" for a
                // state whose flag IS present; only the "no flag" case should show).
                if (cv != null && !(cv instanceof String s && s.isBlank())) {
                    o.put(ref.name(), cv);
                }
            }
            return o;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object item : c) {
                Object cv = convert(item, seen);
                if (cv != null) out.add(cv);
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            List<Object> out = new ArrayList<>();
            for (Object item : m.values()) {
                Object cv = convert(item, seen);
                if (cv != null) out.add(cv);
            }
            return out;
        }
        // A live Swing ImagePane can't be serialized into the pool — persist it as a
        // metadata-only WikidataMediaValue (label + source url + svg), which round-trips
        // and renders back to an ImagePane on load via FieldKind.MEDIA.
        if (v instanceof ImagePane p) {
            return toMediaValue(p);
        }
        // A hand-written domain's enum (e.g. NobelPrize.Domain) can't round-trip through
        // the pool's locked-down Jackson typing — store its display string, which becomes
        // a plain scalar facet dimension.
        if (v instanceof Enum<?> e) {
            return e.toString();
        }
        return v;
    }

    private static Object toMediaValue(ImagePane p) {
        CachedImage image = p.getCachedImage();
        if (image == null) {
            return null;   // an in-memory-only image (no source) — nothing to persist
        }
        String url = image.sourceUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        return new WikidataMediaValue(p.getTitle(), url, image.isSvg());
    }
}
