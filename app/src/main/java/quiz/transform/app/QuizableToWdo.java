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
        return roots;
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
            String id = q.getIdentifier();
            if (id == null || id.isBlank()) {
                id = q.getDisplayName();
            }
            WikidataDynamicObject o = new WikidataDynamicObject(id, q.getDisplayName());
            o.type(q.typeName());
            seen.put(q, o);
            // Copy every field, whichever representation — no instanceof branch.
            objectview.field.FieldSet set = objectview.field.FieldSet.of(q);
            for (objectview.field.FieldRef ref : set.fields()) {
                Object cv = convert(set.read(ref.name()), seen);
                if (cv != null) {
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
