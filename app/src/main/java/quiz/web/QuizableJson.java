package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import objectview.render.FieldLabels;
import objectview.media.ImageRef;
import quiz.Quizable;
import quiz.QuizableAdapter;
import objectview.viewconfig.ViewConfigJsonIO;
import objectview.viewconfig.ViewConfigJsonIO.JsonConfig;

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
 * its fields, mirroring {@code Card.addRenderedField}: scalars
 * become text/list, {@code @Link} becomes link, {@code @Inline}
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
        // The ONE FieldSet bridge (#87): a dynamic property map or declared Java
        // fields behind one interface — no `instanceof DynamicFields` fork.
        return objectview.field.FieldSet.of(owner).read(name);
    }

    /** Render-model for a single named field of {@code owner}, or null. */
    private static QuizableView.Field fieldOfSingle(Quizable owner, String fieldName) {
        // "name" is the display name (skipped as a normal field, but usable
        // as a quiz prompt/answer).
        if ("name".equals(fieldName)) {
            String dn = owner.getDisplayName();
            return dn == null || dn.isBlank() ? null : QuizableView.Field.text("name", dn);
        }

        // One field through the ONE FieldSet bridge (#87) + the shared builder — no
        // `instanceof DynamicFields` fork, no separate dynamic/declared builders.
        objectview.field.FieldSet fs = objectview.field.FieldSet.of(owner);
        objectview.field.FieldRef fr = fs.field(fieldName);
        if (fr == null) {
            return null;
        }
        Object value = fs.read(fieldName);
        if (!QuizableAdapter.isValidQuizValue(value)) {
            return null;
        }
        return buildField(owner.typeName(), owner.getIdentifier(), fr, value,
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

        // Both backings resolve the same way — read through the ONE FieldSet bridge
        // (#87), then stringify. No `instanceof DynamicFields` fork.
        String s = asString(objectview.field.FieldSet.of(owner).read(fieldName));
        return s == null || s.isBlank() ? null : s;
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
            // Enumerate the object's fields through the ONE FieldSet bridge (#87) —
            // declared Java fields OR a dynamic property map, behind one interface —
            // and render each through one builder. No `instanceof DynamicFields` fork.
            List<QuizableView.Field> fields = new ArrayList<>();
            Set<String> structural = STRUCTURAL_BY_TYPE.getOrDefault(type, Set.of());
            objectview.field.FieldSet fs = objectview.field.FieldSet.of(q);
            for (objectview.field.FieldRef fr : fs.fields()) {
                String fn = fr.name();
                // "name" is the display name (rendered from the header, not as a field);
                // "__"-prefixed keys are internal plumbing (e.g. the reify's
                // "__Nomination" statement-list scratch field), never user-facing;
                // structural fields (a reify class's "source" back-ref) are hidden too.
                if ("name".equals(fn) || (fn != null && fn.startsWith("__"))
                        || structural.contains(fn)) {
                    continue;
                }
                Object value = fs.read(fn);
                if (!QuizableAdapter.isValidQuizValue(value)) {
                    continue;
                }
                QuizableView.Field field = buildField(type, id, fr, value, visited);
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

    // Per-type STRUCTURAL fields to hide from cards/facets — plumbing that isn't a
    // user-facing attribute, e.g. a reified statement class's "source" back-reference
    // (provenance). Populated by the serving source (which has the model) at register
    // time; the same convention SnapshotDomain applies on the desktop side.
    private static final Map<String, Set<String>> STRUCTURAL_BY_TYPE =
            new ConcurrentHashMap<>();

    /** Registers the fields to hide for a served type (see {@link #STRUCTURAL_BY_TYPE}). */
    public static void registerStructural(String type, Set<String> fields) {
        if (type != null && !type.isBlank() && fields != null && !fields.isEmpty()) {
            STRUCTURAL_BY_TYPE.put(type, Set.copyOf(fields));
        }
    }

    private static JsonConfig configFor(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        JsonConfig c = CONFIG_CACHE.computeIfAbsent(typeName, t -> {
            JsonConfig loaded =
                    ViewConfigJsonIO.loadJson(
                            ViewConfigJsonIO.fileForType(t));
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

    // The ONE field builder (#87). A declared field's annotation-derived render hints
    // (carried on the FieldRef) take precedence; then the value's SHAPE decides, and
    // shape is backing-agnostic — so a dynamic (map-held) field, which carries no
    // hints, is rendered purely by shape (media/http image, external link, reference,
    // collection, boolean, text). One path serves both representations.
    private static QuizableView.Field buildField(
            String ownerType, String ownerId, objectview.field.FieldRef fr, Object value,
            Set<Object> visited) {

        String name = fr.name();

        // -- declared-field render hints --
        // Server-rendered image bytes (a declared ImageRef); a dynamic value is never
        // an ImageRef, so this is skipped for map fields.
        if (value instanceof ImageRef) {
            String url = "/api/image/"
                    + enc(ownerType) + "/" + enc(ownerId) + "/" + enc(name);
            return QuizableView.Field.image(name, url);
        }
        if (fr.link() && value instanceof String s && !s.isBlank()) {
            return linkField(name, s, fr.linkText());
        }
        if (fr.inline()) {
            List<QuizableView> nodes = inlineNodes(value, visited);
            return nodes.isEmpty() ? null : QuizableView.Field.inline(name, nodes);
        }

        // -- value shape (backing-agnostic) --
        // A media value (e.g. a Commons sky chart, P18) carries its own URL; render it
        // as an image rather than letting it fall through to text (a bare filename).
        if (value instanceof wikidata.explore.extract.WikidataMediaValue media
                && media.hasUrl()) {
            // An http(s) url the browser can fetch directly (e.g. a Commons image);
            // anything else (file:/bundled, from a saved manual domain) must be rendered
            // by the server — same path as a declared ImageRef.
            String url = isHttp(media.url())
                    ? httpsUrl(media.url())
                    : "/api/image/" + enc(ownerType) + "/" + enc(ownerId) + "/" + enc(name);
            return QuizableView.Field.image(name, url);
        }
        if (value instanceof String s && isHttp(s)) {
            // https so it isn't mixed-content-blocked on an https page.
            String url = httpsUrl(s);
            return isImageKey(name)
                    ? QuizableView.Field.image(name, url)       // e.g. a sky chart
                    : linkField(name, url, name);               // e.g. a wikidata link
        }
        if (value instanceof Quizable q) {
            return referenceOrLink(name, q, visited);
        }
        if (value instanceof Collection<?> c) {
            return collectionField(ownerType, ownerId, name, c, visited);
        }
        if (value instanceof Map<?, ?> m) {
            return collectionField(ownerType, ownerId, name, m.values(), visited);
        }
        if (value instanceof Boolean flag) {
            return booleanField(name, flag);
        }
        return QuizableView.Field.text(name, String.valueOf(value));
    }

    // A boolean flag reads as a badge: the humanized field name when true,
    // omitted (null) when false — never "true"/"false".
    private static QuizableView.Field booleanField(String name, boolean flag) {
        return flag ? QuizableView.Field.text(name, FieldLabels.humanize(name)) : null;
    }

    // A reference to a first-class dataset entity (its type was stamped, so
    // typeName() differs from the raw dynamic class name) is navigable in-app,
    // so we emit a lazy ref the client resolves from the store. A bare leaf
    // wikidata entity -- e.g. a constellation's hemisphere or its namesake --
    // isn't in the store, so an internal ref is a dead end; if it carries an
    // external URL (its @Link field) we link out to that page instead.
    private static QuizableView.Field referenceOrLink(
            String name, Quizable q, Set<Object> visited) {
        boolean domainType = !q.typeName().equals(q.getClass().getSimpleName());
        if (!domainType) {
            String ext = externalUrl(q);
            if (ext != null) {
                return QuizableView.Field.link(name, q.getDisplayName(), ext);
            }
        }
        return QuizableView.Field.ref(name, ref(q, visited));
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

    private static QuizableView.Field collectionField(
            String ownerType, String ownerId, String name, Collection<?> items,
            Set<Object> visited) {

        // A collection of images (e.g. flag versions): one indexed image URL
        // per item, by position in the collection.
        List<String> imageUrls = new ArrayList<>();
        int idx = 0;
        for (Object item : items) {
            if (item instanceof wikidata.explore.extract.WikidataMediaValue m
                    && m.hasUrl() && isHttp(m.url())) {
                imageUrls.add(httpsUrl(m.url()));             // browser-fetchable directly
            } else if (item instanceof ImageRef
                    || (item instanceof wikidata.explore.extract.WikidataMediaValue m2
                            && m2.hasUrl())) {
                imageUrls.add("/api/image/"                  // server-rendered (file:/bundled)
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
                refs.add(ref(q, visited));
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

    private static QuizableView.Ref ref(Quizable q, Set<Object> visited) {
        // A value object isn't in the pool, so its chip can't be FETCHED to expand —
        // carry its contents INLINE so the chip expands from embedded data. An entity's
        // chip stays lazy (fetched by id). Rendering shape (chip) is uniform either way.
        QuizableView inline = isValueObject(q) ? of(q, visited) : null;
        return new QuizableView.Ref(
                q.getIdentifier(), q.getDisplayName(), q.typeName(), thumbUrl(q), inline);
    }

    /** A small render URL for {@code q}'s first media field (e.g. a Laureate's portrait),
     *  so a chip / list item can show an avatar — null if it has none. Http media goes
     *  direct; local (file:/bundled) media routes through the server render endpoint. */
    public static String thumbUrl(Quizable q) {
        objectview.field.FieldSet fs = objectview.field.FieldSet.of(q);
        for (objectview.field.FieldRef fr : fs.fields()) {
            Object v = fs.read(fr.name());
            if (v instanceof wikidata.explore.extract.WikidataMediaValue m && m.hasUrl()) {
                return isHttp(m.url())
                        ? httpsUrl(m.url())
                        : imageApi(q.typeName(), q.getIdentifier(), fr.name());
            }
            if (v instanceof objectview.media.ImageRef) {
                return imageApi(q.typeName(), q.getIdentifier(), fr.name());
            }
        }
        return null;
    }

    private static String imageApi(String type, String id, String field) {
        return "/api/image/" + enc(type) + "/" + enc(id) + "/" + enc(field);
    }

    /** A value object either by its Java type (hand-written domain) or by the runtime
     *  flag carried on a snapshot-loaded {@link WikidataDynamicObject}. */
    private static boolean isValueObject(Quizable q) {
        return q instanceof quiz.ValueObject
                || (q instanceof wikidata.explore.extract.WikidataDynamicObject w
                        && w.isValueObject());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
