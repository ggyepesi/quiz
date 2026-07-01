package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import quiz.DynamicFields;
import quiz.ImageRef;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.annotations.Link;
import quiz.ui.viewconfig.QuizablePanelConfigJsonIO;
import quiz.ui.viewconfig.QuizablePanelConfigJsonIO.JsonConfig;

import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a {@link QuizableView} from any {@link Quizable} by reflecting over
 * its fields, mirroring {@code QuizablePanel.addRenderedField}: scalars
 * become text/list, {@code @Link} becomes link, {@code @QuizableInline}
 * embeds nested views, and any other Quizable (single or in a
 * collection/map) becomes a lazy reference.
 */
public final class QuizableJson {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private QuizableJson() {}

    public static QuizableView of(Quizable q) {
        return of(q, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    public static String json(Quizable q) {
        try {
            return MAPPER.writeValueAsString(of(q));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize " + q, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Render-model for a (possibly dotted) field path of {@code owner}, or
     *  null. A path like {@code namedAfter.area} walks the reference(s) then
     *  reads the leaf field — so quizzes can use nested fields. */
    public static QuizableView.Field fieldOf(Quizable owner, String path) {
        int dot = path == null ? -1 : path.indexOf('.');
        if (dot < 0) {
            return fieldOfSingle(owner, path);
        }
        String seg = path.substring(0, dot);
        String rest = path.substring(dot + 1);

        // Fan a collection segment out to ALL members so e.g.
        // sharesBorderWith.chart yields every neighbour's chart (an image
        // strip), not just the first.
        List<Quizable> targets = stepIntoAll(owner, seg);
        if (targets.isEmpty()) {
            return null;
        }
        if (targets.size() == 1) {
            return fieldOf(targets.get(0), rest);
        }

        List<QuizableView.Field> leaves = new ArrayList<>();
        for (Quizable t : targets) {
            QuizableView.Field f = fieldOf(t, rest);
            if (f != null) {
                leaves.add(f);
            }
        }
        return combineLeaves(lastSegment(path), leaves);
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }

    // Combines the same leaf field gathered from several collection members:
    // images become one "images" strip; anything else becomes a "list" of each
    // member's value (so e.g. sharesBorderWith.name lists every neighbour name).
    private static QuizableView.Field combineLeaves(
            String name, List<QuizableView.Field> leaves) {
        if (leaves.isEmpty()) {
            return null;
        }
        boolean allImages = leaves.stream().allMatch(f -> "image".equals(f.kind()));
        if (allImages) {
            List<String> urls = new ArrayList<>();
            for (QuizableView.Field f : leaves) {
                if (f.url() != null) {
                    urls.add(f.url());
                }
            }
            return urls.isEmpty() ? null : QuizableView.Field.images(name, urls);
        }
        List<String> vals = new ArrayList<>();
        for (QuizableView.Field f : leaves) {
            String s = leafText(f);
            if (s != null && !s.isBlank()) {
                vals.add(s);
            }
        }
        return vals.isEmpty() ? null : QuizableView.Field.list(name, vals);
    }

    private static String leafText(QuizableView.Field f) {
        if (f.value() != null) {
            return f.value();
        }
        if (f.ref() != null) {
            return f.ref().name();
        }
        if (f.values() != null && !f.values().isEmpty()) {
            return String.join(", ", f.values());
        }
        if (f.refs() != null && !f.refs().isEmpty()) {
            List<String> ns = new ArrayList<>();
            for (QuizableView.Ref r : f.refs()) {
                ns.add(r.name());
            }
            return String.join(", ", ns);
        }
        return f.label();
    }

    /** The individual member values of a (possibly dotted) field path: a
     *  collection/map field yields one entry per member; a single field yields
     *  one entry. Lets a quiz ask about ONE member of a set (e.g. one of a
     *  constellation's bordering constellations) instead of the whole joined
     *  list. */
    public static List<String> stringValues(Quizable owner, String path) {
        int dot = path == null ? -1 : path.indexOf('.');
        if (dot < 0) {
            return stringValuesSingle(owner, path);
        }
        Quizable target = stepInto(owner, path.substring(0, dot));
        return target == null ? List.of() : stringValues(target, path.substring(dot + 1));
    }

    private static List<String> stringValuesSingle(Quizable owner, String fieldName) {
        if ("name".equals(fieldName)) {
            String dn = owner.getDisplayName();
            return dn == null || dn.isBlank() ? List.of() : List.of(dn);
        }
        List<String> out = new ArrayList<>();
        collectStrings(rawFieldValue(owner, fieldName), out);
        return out;
    }

    private static void collectStrings(Object v, List<String> out) {
        if (v == null) {
            return;
        }
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                collectStrings(o, out);
            }
            return;
        }
        if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                collectStrings(o, out);
            }
            return;
        }
        String s = asString(v);
        if (s != null && !s.isBlank()) {
            out.add(s);
        }
    }

    /** A plain string value of a (possibly dotted) field path. */
    public static String stringValue(Quizable owner, String path) {
        int dot = path == null ? -1 : path.indexOf('.');
        if (dot < 0) {
            return stringValueSingle(owner, path);
        }
        Quizable target = stepInto(owner, path.substring(0, dot));
        return target == null ? null : stringValue(target, path.substring(dot + 1));
    }

    /** An image strip for a collection image path (e.g. sharesBorderWith.chart)
     *  where each member's image is the name-BLURRING chart endpoint for that
     *  member — used when the member name isn't being revealed, so the chart
     *  doesn't give the answer away. Null if no member has such an image. */
    public static QuizableView.Field blurredImageStrip(Quizable owner, String path) {
        String leaf = lastSegment(path);
        String parent = path.contains(".")
                ? path.substring(0, path.lastIndexOf('.'))
                : "";
        List<String> urls = new ArrayList<>();
        for (Quizable m : resolveAll(owner, parent)) {
            QuizableView.Field lf = fieldOf(m, leaf);
            if (lf != null && "image".equals(lf.kind())) {
                urls.add(BlurredImageService.blurUrl(m.typeName(), m.getIdentifier(), leaf));
            }
        }
        if (urls.isEmpty()) {
            return null;
        }
        return urls.size() == 1
                ? QuizableView.Field.image(leaf, urls.get(0))
                : QuizableView.Field.images(leaf, urls);
    }

    // All objects reached by walking a dotted path, fanning out at every
    // collection segment (so the parent of a strip yields all members).
    private static List<Quizable> resolveAll(Quizable owner, String path) {
        List<Quizable> cur = new ArrayList<>();
        cur.add(owner);
        if (path == null || path.isBlank()) {
            return cur;
        }
        for (String seg : path.split("\\.")) {
            List<Quizable> next = new ArrayList<>();
            for (Quizable o : cur) {
                next.addAll(stepIntoAll(o, seg));
            }
            cur = next;
            if (cur.isEmpty()) {
                break;
            }
        }
        return cur;
    }

    /** The object reached by walking a dotted path (every segment a reference),
     *  or {@code owner} for an empty path, or null if a step has no target. */
    public static Quizable resolvePath(Quizable owner, String path) {
        if (path == null || path.isBlank()) {
            return owner;
        }
        Quizable cur = owner;
        for (String seg : path.split("\\.")) {
            if (cur == null) {
                return null;
            }
            cur = stepInto(cur, seg);
        }
        return cur;
    }

    // Resolves one path segment to a referenced Quizable (the first one, if the
    // field is a collection/map of references).
    private static Quizable stepInto(Quizable owner, String segment) {
        if (owner == null || segment == null) {
            return null;
        }
        Object v = rawFieldValue(owner, segment);
        if (v instanceof Quizable q) {
            return q;
        }
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof Quizable q) {
                    return q;
                }
            }
        }
        if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                if (o instanceof Quizable q) {
                    return q;
                }
            }
        }
        return null;
    }

    // All referenced Quizables for a path segment (every member of a
    // collection/map, or the single referenced object).
    private static List<Quizable> stepIntoAll(Quizable owner, String segment) {
        if (owner == null || segment == null) {
            return List.of();
        }
        Object v = rawFieldValue(owner, segment);
        List<Quizable> out = new ArrayList<>();
        if (v instanceof Quizable q) {
            out.add(q);
        } else if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof Quizable q) {
                    out.add(q);
                }
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object o : m.values()) {
                if (o instanceof Quizable q) {
                    out.add(q);
                }
            }
        }
        return out;
    }

    private static Object rawFieldValue(Quizable owner, String name) {
        if (owner instanceof DynamicFields dyn
                && dyn.dynamicFieldValues().containsKey(name)) {
            return dyn.dynamicFieldValues().get(name);
        }
        Field f = QuizableAdapter.getField(owner.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (Exception e) {
            return null;
        }
    }

    /** Render-model for a single named field of {@code owner}, or null. */
    private static QuizableView.Field fieldOfSingle(Quizable owner, String fieldName) {
        // "name" is the display name (skipped as a normal field, but usable
        // as a quiz prompt/answer).
        if ("name".equals(fieldName)) {
            String dn = owner.getDisplayName();
            return dn == null || dn.isBlank() ? null : QuizableView.Field.text("name", dn);
        }

        if (owner instanceof DynamicFields dyn && dyn.dynamicFieldValues().containsKey(fieldName)) {
            Object v = dyn.dynamicFieldValues().get(fieldName);
            return QuizableAdapter.isValidQuizValue(v)
                    ? dynamicField(owner.typeName(), owner.getIdentifier(),
                            fieldName, v, Collections.newSetFromMap(new IdentityHashMap<>()))
                    : null;
        }

        Field f = QuizableAdapter.getField(owner.getClass(), fieldName);
        if (f == null) {
            return null;
        }

        Object value;
        try {
            f.setAccessible(true);
            value = f.get(owner);
        } catch (Exception e) {
            return null;
        }

        if (!QuizableAdapter.isValidQuizValue(value)) {
            return null;
        }

        return field(
                owner.typeName(),
                owner.getIdentifier(),
                f,
                value,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * A plain string value of a field for use as a quiz answer/option:
     * Quizable -> display name, collection/map -> joined items, else the
     * value's string. Null if empty.
     */
    private static String stringValueSingle(Quizable owner, String fieldName) {
        if ("name".equals(fieldName)) {
            String dn = owner.getDisplayName();
            return dn == null || dn.isBlank() ? null : dn;
        }

        if (owner instanceof DynamicFields dyn && dyn.dynamicFieldValues().containsKey(fieldName)) {
            String s = asString(dyn.dynamicFieldValues().get(fieldName));
            return s == null || s.isBlank() ? null : s;
        }

        Field f = QuizableAdapter.getField(owner.getClass(), fieldName);
        if (f == null) {
            return null;
        }

        try {
            f.setAccessible(true);
            Object v = f.get(owner);
            String s = asString(v);
            return s == null || s.isBlank() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Quizable q) {
            return q.getDisplayName();
        }
        if (v instanceof Collection<?> c) {
            return joinItems(c);
        }
        if (v instanceof Map<?, ?> m) {
            return joinItems(m.values());
        }
        return String.valueOf(v);
    }

    private static String joinItems(Collection<?> items) {
        List<String> parts = new ArrayList<>();
        for (Object item : items) {
            String s = item instanceof Quizable q ? q.getDisplayName() : String.valueOf(item);
            if (s != null && !s.isBlank()) {
                parts.add(s);
            }
        }
        return String.join(", ", parts);
    }

    private static QuizableView of(Quizable q, Set<Object> visited) {
        String id = q.getIdentifier();
        String name = q.getDisplayName();
        String type = q.typeName();

        // Cycle guard: a Quizable already on the current path renders shallow.
        if (!visited.add(q)) {
            return new QuizableView(id, name, type, List.of());
        }

        try {
            // Generated/dynamic objects keep their data in a property map, not
            // declared fields — render those entries as first-class fields.
            if (q instanceof DynamicFields dyn) {
                return new QuizableView(id, name, type,
                        applyViewConfig(type, dynamicFields(type, id, dyn, visited)));
            }

            List<QuizableView.Field> fields = new ArrayList<>();

            for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
                if ("name".equals(f.getName())) {
                    continue;
                }

                Object value;
                try {
                    value = f.get(q);
                } catch (Exception e) {
                    continue;
                }

                if (!QuizableAdapter.isValidQuizValue(value)) {
                    continue;
                }

                QuizableView.Field field = field(type, id, f, value, visited);
                if (field != null) {
                    fields.add(field);
                }
            }

            return new QuizableView(id, name, type, applyViewConfig(type, fields));
        } finally {
            visited.remove(q);
        }
    }

    // ---- View config (shared with the desktop) -----------------------------

    // Per-type view config, keyed by domain typeName so one config drives both
    // surfaces. A sentinel marks "looked up, none found" so we don't re-read.
    private static final JsonConfig NO_CONFIG = new JsonConfig();
    private static final Map<String, JsonConfig> CONFIG_CACHE =
            new ConcurrentHashMap<>();

    private static JsonConfig configFor(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        JsonConfig c = CONFIG_CACHE.computeIfAbsent(typeName, t -> {
            JsonConfig loaded =
                    QuizablePanelConfigJsonIO.loadJson(
                            QuizablePanelConfigJsonIO.fileForType(t));
            return loaded == null ? NO_CONFIG : loaded;
        });
        return c == NO_CONFIG ? null : c;
    }

    /** Applies the per-type view config to the serialized fields: configured
     *  fields first in config order; the rest appended only when allFields is
     *  set (else hidden). No config -> unchanged (show everything). */
    private static List<QuizableView.Field> applyViewConfig(
            String type, List<QuizableView.Field> fields) {

        JsonConfig cfg = configFor(type);
        if (cfg == null || cfg.fields == null || cfg.fields.isEmpty()) {
            return fields;
        }

        Map<String, QuizableView.Field> byName = new LinkedHashMap<>();
        for (QuizableView.Field f : fields) {
            byName.put(f.name(), f);
        }

        List<QuizableView.Field> out = new ArrayList<>();
        for (String fieldName : cfg.fields.keySet()) {
            QuizableView.Field f = byName.remove(fieldName);
            if (f != null) {
                out.add(f);
            }
        }
        if (cfg.allFields) {
            out.addAll(byName.values());
        }
        return out;
    }

    private static List<QuizableView.Field> dynamicFields(
            String type, String id, DynamicFields dyn, Set<Object> visited) {

        List<QuizableView.Field> fields = new ArrayList<>();
        for (Map.Entry<String, Object> e : dyn.dynamicFieldValues().entrySet()) {
            // "__"-prefixed keys are internal plumbing (e.g. the reify's
            // "__Nomination" statement-list scratch field), never user-facing data.
            if (e.getKey() != null && e.getKey().startsWith("__")) {
                continue;
            }
            Object value = e.getValue();
            if (!QuizableAdapter.isValidQuizValue(value)) {
                continue;
            }
            QuizableView.Field field = dynamicField(type, id, e.getKey(), value, visited);
            if (field != null) {
                fields.add(field);
            }
        }
        return fields;
    }

    // Field from a raw (name, value) pair -- dynamic entries carry no @Link /
    // @QuizableInline annotations. An http(s) value under an image-ish key is
    // an external image (e.g. a Commons sky chart) the client loads directly.
    private static QuizableView.Field dynamicField(
            String ownerType, String ownerId, String name, Object value, Set<Object> visited) {

        // A media value (e.g. a Commons sky chart, P18) carries its own URL;
        // render it as an image rather than letting it fall through to text
        // (which printed the bare filename like "CamelopardalisCC.jpg").
        if (value instanceof wikidata.explore.extract.WikidataMediaValue media
                && media.hasUrl()) {
            return QuizableView.Field.image(name, httpsUrl(media.url()));
        }
        if (value instanceof String s && isHttp(s)) {
            // https so it isn't mixed-content-blocked on an https page.
            String url = httpsUrl(s);
            return isImageKey(name)
                    ? QuizableView.Field.image(name, url)       // e.g. a sky chart
                    : linkField(name, url, name);               // e.g. a wikidata link
        }
        if (value instanceof Quizable q) {
            return referenceOrLink(name, q);
        }
        if (value instanceof Collection<?> c) {
            return collectionField(ownerType, ownerId, name, c);
        }
        if (value instanceof Map<?, ?> m) {
            return collectionField(ownerType, ownerId, name, m.values());
        }
        return QuizableView.Field.text(name, String.valueOf(value));
    }

    // A reference to a first-class dataset entity (its type was stamped, so
    // typeName() differs from the raw dynamic class name) is navigable in-app,
    // so we emit a lazy ref the client resolves from the store. A bare leaf
    // wikidata entity -- e.g. a constellation's hemisphere or its namesake --
    // isn't in the store, so an internal ref is a dead end; if it carries an
    // external URL (its @Link field) we link out to that page instead.
    private static QuizableView.Field referenceOrLink(String name, Quizable q) {
        boolean domainType = !q.typeName().equals(q.getClass().getSimpleName());
        if (!domainType) {
            String ext = externalUrl(q);
            if (ext != null) {
                return QuizableView.Field.link(name, q.getDisplayName(), ext);
            }
        }
        return QuizableView.Field.ref(name, ref(q));
    }

    // The value of the first non-blank @Link (URL) field on the object, if any
    // -- e.g. WikidataDynamicObject.wikidataUrl.
    private static String externalUrl(Quizable q) {
        for (Field f : QuizableAdapter.getAllFields(q.getClass())) {
            if (!QuizableAdapter.isLinkField(f)) {
                continue;
            }
            try {
                f.setAccessible(true);
                if (f.get(q) instanceof String s && isHttp(s)) {
                    return s;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean isImageKey(String name) {
        String n = name.toLowerCase();
        return n.contains("chart") || n.contains("image") || n.contains("img")
                || n.contains("photo") || n.contains("logo") || n.contains("portrait")
                || n.contains("flag");
    }

    private static boolean isHttp(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }

    // Upgrade http→https so an image/link isn't mixed-content-blocked when the
    // page itself is served over https.
    private static String httpsUrl(String s) {
        return s != null && s.startsWith("http://") ? "https://" + s.substring(7) : s;
    }

    private static QuizableView.Field field(
            String ownerType, String ownerId, Field f, Object value, Set<Object> visited) {

        String name = f.getName();

        if (value instanceof ImageRef) {
            String url = "/api/image/"
                    + enc(ownerType) + "/" + enc(ownerId) + "/" + enc(name);
            return QuizableView.Field.image(name, url);
        }

        if (QuizableAdapter.isLinkField(f) && value instanceof String s && !s.isBlank()) {
            Link link = f.getAnnotation(Link.class);
            return linkField(name, s, link == null ? "" : link.text());
        }

        if (QuizableAdapter.isQuizableInline(f)) {
            List<QuizableView> nodes = inlineNodes(value, visited);
            return nodes.isEmpty() ? null : QuizableView.Field.inline(name, nodes);
        }

        if (value instanceof Quizable q) {
            return QuizableView.Field.ref(name, ref(q));
        }

        if (value instanceof Collection<?> c) {
            return collectionField(ownerType, ownerId, name, c);
        }

        if (value instanceof Map<?, ?> m) {
            return collectionField(ownerType, ownerId, name, m.values());
        }

        return QuizableView.Field.text(name, String.valueOf(value));
    }

    private static QuizableView.Field collectionField(
            String ownerType, String ownerId, String name, Collection<?> items) {

        // A collection of images (e.g. flag versions): one indexed image URL
        // per item, by position in the collection.
        List<String> imageUrls = new ArrayList<>();
        int idx = 0;
        for (Object item : items) {
            if (item instanceof ImageRef) {
                imageUrls.add("/api/image/"
                        + enc(ownerType) + "/" + enc(ownerId) + "/" + enc(name) + "/" + idx);
            }
            idx++;
        }
        if (!imageUrls.isEmpty()) {
            return QuizableView.Field.images(name, imageUrls);
        }

        List<QuizableView.Ref> refs = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Quizable q) {
                refs.add(ref(q));
            }
        }
        if (!refs.isEmpty()) {
            return QuizableView.Field.refs(name, refs);
        }

        List<String> values = new ArrayList<>();
        for (Object item : items) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }

        return values.isEmpty() ? null : QuizableView.Field.list(name, values);
    }

    private static List<QuizableView> inlineNodes(Object value, Set<Object> visited) {
        List<QuizableView> nodes = new ArrayList<>();

        if (value instanceof Quizable q) {
            nodes.add(of(q, visited));
        } else if (value instanceof Collection<?> c) {
            for (Object item : c) {
                if (item instanceof Quizable q) {
                    nodes.add(of(q, visited));
                }
            }
        } else if (value instanceof Map<?, ?> m) {
            for (Object item : m.values()) {
                if (item instanceof Quizable q) {
                    nodes.add(of(q, visited));
                }
            }
        }

        return nodes;
    }

    private static QuizableView.Field linkField(String name, String rawValue, String annotationText) {
        String label = null;
        String url = rawValue;

        int bar = rawValue.indexOf('|');
        if (bar > 0 && bar < rawValue.length() - 1) {
            label = rawValue.substring(0, bar).trim();
            url = rawValue.substring(bar + 1).trim();
        }

        if (annotationText != null && !annotationText.isBlank()) {
            label = annotationText.trim();
        }

        return QuizableView.Field.link(name, label == null ? url : label, url);
    }

    private static QuizableView.Ref ref(Quizable q) {
        return new QuizableView.Ref(
                q.getIdentifier(), q.getDisplayName(), q.typeName());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
