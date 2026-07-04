package wikidata.explore.extract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves/loads extracted WikidataDynamicObject graphs.
 *
 * Bridge:
 *   RuleTreeExtractor output -> JSON cache -> GeneratedKnowledgeSet -> QuizFactory
 *
 * <p>One QID maps to one object. Reference fields (e.g. a constellation's
 * neighbours, which point at other constellations) make the graph cyclic and
 * deep, so objects are <b>not</b> inlined recursively — that would loop forever
 * or blow Jackson's nesting-depth limit. Instead the graph is flattened: every
 * reachable object is written once into a pool keyed by qid, and object-valued
 * fields are stored as {@link Ref} markers (just a qid) and re-linked on load.
 *
 * <p>Snapshots written by the older inlined format (no {@code entities} pool)
 * still load via the legacy path.
 */
public class WikidataDynamicObjectJsonStore {

    private static final int FORMAT_VERSION = 2;

    private final ObjectMapper mapper;

    public WikidataDynamicObjectJsonStore() {
        mapper = new ObjectMapper();

        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        mapper.activateDefaultTypingAsProperty(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("wikidata.explore")
                        .allowIfSubType("java.util")
                        .allowIfSubType("java.lang")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                "@class");

        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    public void save(List<WikidataDynamicObject> objects, File file)
            throws IOException {

        if (objects == null) objects = List.of();

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // Group EVERY reachable instance by qid (following object-valued fields
        // through cycles). A qid can have several instances — a rich carrier plus
        // field-poor reference copies made outside the registry — so we MERGE their
        // fields (union) into one entity, keeping the richest data. Otherwise a poor
        // copy reached first would overwrite the carrier, dropping e.g. its `type`.
        LinkedHashMap<String, List<WikidataDynamicObject>> byQid =
                new LinkedHashMap<>();
        java.util.Set<WikidataDynamicObject> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (WikidataDynamicObject o : objects) {
            collect(o, byQid, visited);
        }

        FlatSnapshot snapshot = new FlatSnapshot();
        snapshot.version = FORMAT_VERSION;
        for (WikidataDynamicObject o : objects) {
            String k = keyOf(o);
            if (k != null) snapshot.roots.add(k);
        }
        for (List<WikidataDynamicObject> instances : byQid.values()) {
            snapshot.entities.add(toEntity(instances));
        }

        mapper.writeValue(file, snapshot);
    }

    private void collect(
            WikidataDynamicObject o,
            Map<String, List<WikidataDynamicObject>> byQid,
            java.util.Set<WikidataDynamicObject> visited) {
        if (o == null || !visited.add(o)) return;
        String key = keyOf(o);
        if (key != null) {
            byQid.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
        for (Object v : o.dynamicFields().values()) {
            collectValue(v, byQid, visited);
        }
    }

    private void collectValue(Object v,
            Map<String, List<WikidataDynamicObject>> byQid,
            java.util.Set<WikidataDynamicObject> visited) {
        if (v instanceof WikidataDynamicObject w) {
            collect(w, byQid, visited);
        } else if (v instanceof java.util.Collection<?> col) {
            for (Object e : col) collectValue(e, byQid, visited);
        }
    }

    // One entity DTO per qid, MERGING all instances of that qid: a resolved label
    // over the bare qid, a real class stamp over the untyped sentinel, and the
    // UNION of every field's values (so a rich carrier's `type` isn't lost to a
    // field-poor reference copy). Object-valued fields become qid refs.
    private Entity toEntity(List<WikidataDynamicObject> instances) {
        WikidataDynamicObject first = instances.get(0);
        Entity e = new Entity();
        e.qid = first.qid();

        e.name = first.getDisplayName();
        for (WikidataDynamicObject o : instances) {
            String n = o.getDisplayName();
            if (n != null && !n.equals(e.qid)) { e.name = n; break; }
        }

        e.type = null;
        for (WikidataDynamicObject o : instances) {
            String t = o.typeName();
            if (t != null && !t.isBlank() && !"WikidataDynamicObject".equals(t)) {
                e.type = t;
                break;
            }
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        for (WikidataDynamicObject o : instances) {
            for (Map.Entry<String, Object> f : o.dynamicFields().entrySet()) {
                merged.merge(f.getKey(), f.getValue(),
                        WikidataDynamicObjectJsonStore::unionValues);
            }
        }
        for (Map.Entry<String, Object> f : merged.entrySet()) {
            e.fields.put(f.getKey(), encode(f.getValue()));
        }
        return e;
    }

    /** Union two values of one field across duplicate instances: flatten to a
     *  deduped element list (WDOs by qid, scalars by equals); one element stays a
     *  scalar, several become a list — matching how the pool stores multiplicity. */
    private static Object unionValues(Object a, Object b) {
        List<Object> out = new ArrayList<>();
        addFlattened(out, a);
        addFlattened(out, b);
        return out.size() == 1 ? out.get(0) : out;
    }

    private static void addFlattened(List<Object> out, Object v) {
        if (v instanceof java.util.Collection<?> col) {
            for (Object e : col) addFlattened(out, e);
        } else if (v != null) {
            for (Object existing : out) {
                if (sameValue(existing, v)) return;
            }
            out.add(v);
        }
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == b) return true;
        if (a instanceof WikidataDynamicObject wa
                && b instanceof WikidataDynamicObject wb) {
            return wa.qid() != null && wa.qid().equals(wb.qid());
        }
        return a != null && a.equals(b);
    }

    private Object encode(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return new Ref(keyOf(w));
        }
        if (v instanceof java.util.Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) out.add(encode(e));
            return out;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    public List<WikidataDynamicObject> load(File file) throws IOException {
        JsonNode tree = mapper.readTree(file);
        if (tree == null) {
            return new ArrayList<>();
        }
        if (tree.has("entities")) {
            return loadFlat(mapper.treeToValue(tree, FlatSnapshot.class));
        }
        // Legacy inlined snapshot (objects nested directly, no pool).
        Snapshot legacy = mapper.treeToValue(tree, Snapshot.class);
        return legacy == null || legacy.objects == null
                ? new ArrayList<>()
                : new ArrayList<>(legacy.objects);
    }

    /** Every entity in the snapshot (the whole pool — roots AND referenced
     *  children, e.g. constellations and their stars), re-linked. */
    public List<WikidataDynamicObject> loadAll(File file) throws IOException {
        JsonNode tree = mapper.readTree(file);
        if (tree == null) {
            return new ArrayList<>();
        }
        if (tree.has("entities")) {
            FlatSnapshot snapshot = mapper.treeToValue(tree, FlatSnapshot.class);
            return snapshot == null
                    ? new ArrayList<>()
                    : new ArrayList<>(buildEntities(snapshot).values());
        }
        Snapshot legacy = mapper.treeToValue(tree, Snapshot.class);
        return legacy == null || legacy.objects == null
                ? new ArrayList<>()
                : new ArrayList<>(legacy.objects);
    }

    private Map<String, WikidataDynamicObject> buildEntities(FlatSnapshot snapshot) {
        // Build shells first so refs (including cycles) resolve to one instance
        // per qid; carry the persisted type so multi-class snapshots round-trip.
        Map<String, WikidataDynamicObject> byKey = new LinkedHashMap<>();
        for (Entity e : snapshot.entities) {
            WikidataDynamicObject o = new WikidataDynamicObject(e.qid, e.name);
            if (e.type != null && !e.type.isBlank()) {
                o.type(e.type);
            }
            byKey.put(e.qid, o);
        }
        for (Entity e : snapshot.entities) {
            WikidataDynamicObject o = byKey.get(e.qid);
            for (Map.Entry<String, Object> entry : e.fields.entrySet()) {
                o.dynamicFields().put(entry.getKey(), decode(entry.getValue(), byKey));
            }
        }
        return byKey;
    }

    private List<WikidataDynamicObject> loadFlat(FlatSnapshot snapshot) {
        if (snapshot == null || snapshot.entities == null) {
            return new ArrayList<>();
        }

        Map<String, WikidataDynamicObject> byKey = buildEntities(snapshot);

        List<WikidataDynamicObject> roots = new ArrayList<>();
        List<String> rootKeys = snapshot.roots == null || snapshot.roots.isEmpty()
                ? new ArrayList<>(byKey.keySet())  // tolerate a pool with no explicit roots
                : snapshot.roots;
        for (String k : rootKeys) {
            WikidataDynamicObject o = byKey.get(k);
            if (o != null) roots.add(o);
        }
        return roots;
    }

    private Object decode(Object v, Map<String, WikidataDynamicObject> byKey) {
        if (v instanceof Ref ref) {
            return byKey.get(ref.qid);
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) out.add(decode(e, byKey));
            return out;
        }
        return v;
    }

    private static String keyOf(WikidataDynamicObject o) {
        if (o == null) return null;
        String qid = o.qid();
        return qid == null || qid.isBlank() ? null : qid;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ------------------------------------------------------------------
    // On-disk shapes
    // ------------------------------------------------------------------

    /** A reference to another entity in the same snapshot, by qid. */
    public static class Ref {
        public String qid;

        public Ref() {}

        public Ref(String qid) {
            this.qid = qid;
        }
    }

    public static class Entity {
        public String qid;
        public String name;
        // The stamped domain class (e.g. "Constellation", "Star"); null for an
        // untyped leaf reference. Lets one snapshot carry several classes.
        public String type;
        public Map<String, Object> fields = new LinkedHashMap<>();
    }

    /** Flattened snapshot: a root-qid list plus a deduped entity pool. */
    public static class FlatSnapshot {
        public int version = FORMAT_VERSION;
        public List<String> roots = new ArrayList<>();
        public List<Entity> entities = new ArrayList<>();
    }

    /**
     * Legacy format: objects with their references inlined recursively. Kept
     * (same class name as the old writer used) so pre-existing snapshots —
     * whose {@code @class} names this type — still load.
     */
    public static class Snapshot {
        public int version = 1;
        public List<WikidataDynamicObject> objects = new ArrayList<>();
    }
}
