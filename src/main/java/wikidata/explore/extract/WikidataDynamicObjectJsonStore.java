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

        // Collect every reachable object once (deduped by qid), following
        // object-valued fields through cycles.
        LinkedHashMap<String, WikidataDynamicObject> pool = new LinkedHashMap<>();
        for (WikidataDynamicObject o : objects) {
            collect(o, pool);
        }

        FlatSnapshot snapshot = new FlatSnapshot();
        snapshot.version = FORMAT_VERSION;
        for (WikidataDynamicObject o : objects) {
            String k = keyOf(o);
            if (k != null) snapshot.roots.add(k);
        }
        for (WikidataDynamicObject o : pool.values()) {
            snapshot.entities.add(toEntity(o));
        }

        mapper.writeValue(file, snapshot);
    }

    private void collect(
            WikidataDynamicObject o, Map<String, WikidataDynamicObject> pool) {
        if (o == null) return;
        String key = keyOf(o);
        if (key == null || pool.containsKey(key)) return;
        pool.put(key, o);
        for (Object v : o.dynamicFields().values()) {
            collectValue(v, pool);
        }
    }

    private void collectValue(Object v, Map<String, WikidataDynamicObject> pool) {
        if (v instanceof WikidataDynamicObject w) {
            collect(w, pool);
        } else if (v instanceof List<?> list) {
            for (Object e : list) collectValue(e, pool);
        }
    }

    // An entity DTO whose object-valued fields are replaced by qid refs.
    private Entity toEntity(WikidataDynamicObject o) {
        Entity e = new Entity();
        e.qid = o.qid();
        e.name = o.getDisplayName();
        for (Map.Entry<String, Object> entry : o.dynamicFields().entrySet()) {
            e.fields.put(entry.getKey(), encode(entry.getValue()));
        }
        return e;
    }

    private Object encode(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return new Ref(keyOf(w));
        }
        if (v instanceof List<?> list) {
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

    private List<WikidataDynamicObject> loadFlat(FlatSnapshot snapshot) {
        if (snapshot == null || snapshot.entities == null) {
            return new ArrayList<>();
        }

        // Build shells first so refs (including cycles) resolve to one instance
        // per qid.
        Map<String, WikidataDynamicObject> byKey = new LinkedHashMap<>();
        for (Entity e : snapshot.entities) {
            byKey.put(e.qid, new WikidataDynamicObject(e.qid, e.name));
        }
        for (Entity e : snapshot.entities) {
            WikidataDynamicObject o = byKey.get(e.qid);
            for (Map.Entry<String, Object> entry : e.fields.entrySet()) {
                o.dynamicFields().put(entry.getKey(), decode(entry.getValue(), byKey));
            }
        }

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
