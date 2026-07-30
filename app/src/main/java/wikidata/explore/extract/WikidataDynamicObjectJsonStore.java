package wikidata.explore.extract;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import quiz.transform.ui.DomainModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final int FORMAT_VERSION = 5;

    // Object identity is ⟨typeKey, qid⟩, not the bare qid — so two entities that merely
    // share a name across types (a State "France" vs a ViewableGroup "France") never
    // merge. Pool key + Ref markers carry both parts. Keep the separator escaped: a raw
    // NUL byte makes this Java source look binary to Git and invalid to javac.
    private static final char SEP = '\0';

    private static String compositeKey(String type, String qid) {
        if (qid == null || qid.isBlank()) {
            return null;
        }
        return (type == null ? "" : type) + SEP + qid;
    }

    /** The identity type part: the real type key for a stamped entity, else blank — an
     *  untyped reference copy carries no type of its own and is absorbed into the single
     *  stamped entity for its qid. */
    private static String typePart(WikidataDynamicObject o) {
        return o != null && o.hasTypeStamp() ? o.typeKey() : "";
    }

    /** Pool/ref key: ⟨typeKey, qid⟩. */
    private static String poolKey(WikidataDynamicObject o) {
        return o == null ? null : compositeKey(typePart(o), o.qid());
    }

    /** The qid part of a composite key — the whole string when it has no separator (e.g.
     *  a pre-v4 root persisted as a bare qid). */
    private static String qidPart(String key) {
        if (key == null) {
            return null;
        }
        int i = key.indexOf(SEP);
        return i < 0 ? key : key.substring(i + 1);
    }

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
        saveWithFieldGraph(objects, file);
    }

    /** Saves the snapshot and returns the graph accumulated by the same walk. */
    public SnapshotFieldGraph saveWithFieldGraph(
            List<WikidataDynamicObject> objects, File file)
            throws IOException {
        return saveWithFieldGraph(objects, file, null);
    }

    /**
     * Saves values and enriches the observed field graph with the producing
     * domain's declared schema. This preserves null fields and empty typed
     * collections without writing placeholder values into the instances.
     */
    public SnapshotFieldGraph saveWithFieldGraph(
            List<WikidataDynamicObject> objects, File file, DomainModel schema)
            throws IOException {
        return saveWithFieldGraph(objects, List.of(), file, schema);
    }

    public SnapshotFieldGraph saveWithFieldGraph(
            List<WikidataDynamicObject> memberRoots,
            List<WikidataDynamicObject> groupRoots,
            File file,
            DomainModel schema)
            throws IOException {

        if (memberRoots == null) memberRoots = List.of();
        if (groupRoots == null) groupRoots = List.of();

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
        SnapshotFieldGraph.Builder fieldGraph = SnapshotFieldGraph.builder();
        java.util.Set<WikidataDynamicObject> visited =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (WikidataDynamicObject o : memberRoots) {
            collect(o, byQid, visited, fieldGraph);
        }
        for (WikidataDynamicObject o : groupRoots) {
            collect(o, byQid, visited, fieldGraph);
        }
        fieldGraph.declare(schema);
        fieldGraph.markMembers(memberRoots);

        FlatSnapshot snapshot = new FlatSnapshot();
        snapshot.version = FORMAT_VERSION;
        snapshot.fieldGraph = fieldGraph.build();
        for (WikidataDynamicObject o : memberRoots) {
            String k = poolKey(o);
            // Roots are MEMBERS — entities stamped with a modeled class. An unstamped
            // pool entity is a reference target only (e.g. an INLINE `type`/P31 field's
            // class values like "film"/"human", pulled into the shared registry): it
            // stays in `entities` via the collect() reachability above, but must NOT
            // become a top-level member/root.
            if (k != null && o.hasTypeStamp()) snapshot.roots.add(k);
        }
        for (WikidataDynamicObject o : groupRoots) {
            String k = poolKey(o);
            if (k != null && o.hasTypeStamp()) snapshot.groupRoots.add(k);
        }
        // One qid can now yield SEVERAL entities — one per distinct real type — so a State
        // "France" and a ViewableGroup "France" stay separate ⟨type, qid⟩ objects instead
        // of merging. An untyped reference copy is absorbed into the single stamped entity.
        for (List<WikidataDynamicObject> instances : byQid.values()) {
            snapshot.entities.addAll(toEntities(instances));
        }

        mapper.writeValue(file, snapshot);
        return snapshot.fieldGraph;
    }

    private void collect(
            WikidataDynamicObject o,
            Map<String, List<WikidataDynamicObject>> byQid,
            java.util.Set<WikidataDynamicObject> visited,
            SnapshotFieldGraph.Builder fieldGraph) {
        if (o == null || !visited.add(o)) return;
        fieldGraph.observe(o);
        String key = keyOf(o);
        // A value object is inlined by encode(), NOT pooled — but still recurse its
        // fields so nested ENTITIES (e.g. a Laureate under an inlined wrapper) are pooled.
        if (key != null && !o.isValueObject()) {
            byQid.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }
        for (Object v : o.dynamicFields().values()) {
            collectValue(v, byQid, visited, fieldGraph);
        }
    }

    private void collectValue(Object v,
            Map<String, List<WikidataDynamicObject>> byQid,
            java.util.Set<WikidataDynamicObject> visited,
            SnapshotFieldGraph.Builder fieldGraph) {
        if (v instanceof WikidataDynamicObject w) {
            collect(w, byQid, visited, fieldGraph);
        } else if (v instanceof java.util.Collection<?> col) {
            for (Object e : col) collectValue(e, byQid, visited, fieldGraph);
        } else if (v instanceof java.util.Map<?, ?> map) {
            for (Object e : map.values()) {
                collectValue(e, byQid, visited, fieldGraph);
            }
        }
    }

    /** Split one qid's instances by their real type key into one entity per type, so
     *  different types sharing a name stay distinct. Instances with NO real type (bare
     *  reference copies) are absorbed into the single stamped entity; only when there are
     *  genuinely 2+ real types do they split, with any untyped remainder kept on its own. */
    private List<Entity> toEntities(List<WikidataDynamicObject> instances) {
        LinkedHashMap<String, List<WikidataDynamicObject>> byType = new LinkedHashMap<>();
        List<WikidataDynamicObject> untyped = new ArrayList<>();
        for (WikidataDynamicObject o : instances) {
            if (o.hasTypeStamp()) {
                byType.computeIfAbsent(o.typeKey(), k -> new ArrayList<>()).add(o);
            } else {
                untyped.add(o);
            }
        }
        List<Entity> out = new ArrayList<>();
        if (byType.size() <= 1) {
            String type = byType.isEmpty() ? null : byType.keySet().iterator().next();
            out.add(toEntity(instances, type));   // merge everything (untyped absorbed)
        } else {
            for (Map.Entry<String, List<WikidataDynamicObject>> t : byType.entrySet()) {
                out.add(toEntity(t.getValue(), t.getKey()));
            }
            if (!untyped.isEmpty()) {
                out.add(toEntity(untyped, null));
            }
        }
        return out;
    }

    // One entity DTO per ⟨typeKey, qid⟩, MERGING its instances: a resolved label
    // over the bare qid, a real class stamp over the untyped sentinel, and the
    // UNION of every field's values (so a rich carrier's `type` isn't lost to a
    // field-poor reference copy). Object-valued fields become ⟨type, qid⟩ refs.
    private Entity toEntity(List<WikidataDynamicObject> instances, String typeKey) {
        WikidataDynamicObject first = instances.get(0);
        Entity e = new Entity();
        e.qid = first.qid();
        e.typeKey = typeKey;
        for (WikidataDynamicObject o : instances) {
            e.structuralObject |= o.isStructuralObject();
            if (!o.structuralPath().isEmpty()) {
                e.structuralPath = new ArrayList<>(o.structuralPath());
            }
        }

        e.name = first.getDisplayName();
        for (WikidataDynamicObject o : instances) {
            String n = o.getDisplayName();
            if (n != null && !n.equals(e.qid)) { e.name = n; break; }
        }
        for (WikidataDynamicObject o : instances) {
            String label = o.getReferenceLabel();
            if (label != null && !label.isBlank()
                    && !label.equals(o.getDisplayName())) {
                e.referenceLabel = label;
                break;
            }
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
            return wa.qid() != null
                    && wa.qid().equals(wb.qid())
                    && java.util.Objects.equals(typePart(wa), typePart(wb));
        }
        return a != null && a.equals(b);
    }

    private Object encode(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            // A value object is serialized INLINE (a nested Entity) rather than a Ref to
            // a pooled entity — it has no identity and belongs to this parent.
            return w.isValueObject() ? inlineEntity(w) : new Ref(typePart(w), w.qid());
        }
        if (v instanceof aux.FlexibleDate d) {
            // The compact form carries the precision ("1959" vs "1959-04-06"),
            // so a marker string is enough to restore the typed date on load.
            return new DateVal(d.format());
        }
        if (v instanceof java.util.Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) out.add(encode(e));
            return out;
        }
        if (v instanceof java.util.Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), encode(entry.getValue()));
            }
            return out;
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    public List<WikidataDynamicObject> load(File file) throws IOException {
        return new ArrayList<>(loadAllWithFieldGraph(file).memberRoots());
    }

    /** Every entity in the snapshot (the whole pool — roots AND referenced
     *  children, e.g. constellations and their stars), re-linked. */
    public List<WikidataDynamicObject> loadAll(File file) throws IOException {
        return loadAllWithFieldGraph(file).objects();
    }

    /**
     * Loads instances and their persisted schema from one parse of the snapshot.
     * Pre-v3 snapshots derive the graph once as a compatibility fallback.
     */
    public LoadedSnapshot loadAllWithFieldGraph(File file) throws IOException {
        JsonNode tree = mapper.readTree(file);
        if (tree == null) {
            return new LoadedSnapshot(new ArrayList<>(), new SnapshotFieldGraph(),
                    new ArrayList<>(), new ArrayList<>());
        }
        if (tree.has("entities")) {
            FlatSnapshot snapshot = mapper.treeToValue(tree, FlatSnapshot.class);
            Map<String, WikidataDynamicObject> entities = snapshot == null
                    ? new LinkedHashMap<>() : buildEntities(snapshot);
            List<WikidataDynamicObject> objects =
                    new ArrayList<>(entities.values());
            SnapshotFieldGraph graph = snapshot == null
                    ? null : snapshot.fieldGraph;
            if (graph == null || graph.version != SnapshotFieldGraph.FORMAT_VERSION) {
                graph = SnapshotFieldGraph.derive(objects);
            }
            attachSchemas(objects, graph);
            return new LoadedSnapshot(
                    objects, graph,
                    snapshot == null ? new ArrayList<>()
                            : resolveMemberRoots(
                                    snapshot, tree.has("roots"), objects, entities),
                    snapshot == null ? new ArrayList<>()
                            : resolveGroupRoots(snapshot, objects, entities));
        }
        Snapshot legacy = mapper.treeToValue(tree, Snapshot.class);
        List<WikidataDynamicObject> objects = legacy == null || legacy.objects == null
                ? new ArrayList<>()
                : new ArrayList<>(legacy.objects);
        SnapshotFieldGraph graph = SnapshotFieldGraph.derive(objects);
        attachSchemas(objects, graph);
        return new LoadedSnapshot(objects, graph,
                new ArrayList<>(objects), discoverLegacyGroupRoots(objects, true));
    }

    private static void attachSchemas(
            Collection<WikidataDynamicObject> objects,
            SnapshotFieldGraph graph) {
        if (objects == null || graph == null) {
            return;
        }
        for (WikidataDynamicObject object : objects) {
            object.dynamicFieldSchema(
                    graph.fieldSchema(object.typeName(), Set.of()));
        }
    }

    private static List<WikidataDynamicObject> resolveGroupRoots(
            FlatSnapshot snapshot,
            Collection<WikidataDynamicObject> objects,
            Map<String, WikidataDynamicObject> entities) {
        if (snapshot.version < 5) {
            return discoverLegacyGroupRoots(objects, true);
        }
        if (snapshot.groupRoots == null || snapshot.groupRoots.isEmpty()) {
            return discoverLegacyGroupRoots(objects, false);
        }
        return resolveRoots(snapshot.groupRoots, entities);
    }

    private static List<WikidataDynamicObject> resolveMemberRoots(
            FlatSnapshot snapshot,
            boolean rootsPropertyPresent,
            Collection<WikidataDynamicObject> objects,
            Map<String, WikidataDynamicObject> entities) {
        if (snapshot.roots != null && !snapshot.roots.isEmpty()) {
            return resolveRoots(snapshot.roots, entities);
        }
        // Before v5, or when an older/foreign flat pool omitted the property
        // altogether, the historical interpretation was "every entity is a root".
        if (snapshot.version < 5 || !rootsPropertyPresent) {
            return new ArrayList<>(objects);
        }
        // In v5 an explicitly present empty list is meaningful: the domain has no
        // ordinary member roots (it may, for example, contain only group roots).
        return new ArrayList<>();
    }

    /** Compatibility only: formats before v5 did not persist group-root refs. */
    private static List<WikidataDynamicObject> discoverLegacyGroupRoots(
            Collection<WikidataDynamicObject> objects,
            boolean includeOrdinaryGroups) {
        java.util.Set<WikidataDynamicObject> reachable =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>());
        for (WikidataDynamicObject object : objects) {
            collectReachableObjects(object, reachable);
        }
        List<WikidataDynamicObject> roots = new ArrayList<>();
        for (WikidataDynamicObject object : reachable) {
            if (!"ViewableGroup".equals(object.typeName())
                    || !includeOrdinaryGroups && !object.isStructuralObject()) {
                continue;
            }
            Object parent = object.get("parent");
            if (!(parent instanceof WikidataDynamicObject group)
                    || !"ViewableGroup".equals(group.typeName())) {
                roots.add(object);
            }
        }
        return roots;
    }

    private static void collectReachableObjects(
            Object value,
            java.util.Set<WikidataDynamicObject> reachable) {
        if (value instanceof WikidataDynamicObject object) {
            if (!reachable.add(object)) {
                return;
            }
            for (Object nested : object.dynamicFieldValues().values()) {
                collectReachableObjects(nested, reachable);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object nested : collection) {
                collectReachableObjects(nested, reachable);
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object nested : map.values()) {
                collectReachableObjects(nested, reachable);
            }
        }
    }

    private Map<String, WikidataDynamicObject> buildEntities(FlatSnapshot snapshot) {
        // Build shells first so refs (including cycles) resolve to one instance per
        // ⟨type, qid⟩; carry the persisted type + typeKey so multi-class snapshots and the
        // identity split round-trip.
        Map<String, WikidataDynamicObject> byKey = new LinkedHashMap<>();
        for (Entity e : snapshot.entities) {
            WikidataDynamicObject o = new WikidataDynamicObject(e.qid, e.name);
            if (e.type != null && !e.type.isBlank()) {
                o.type(e.type);
            }
            if (e.typeKey != null && !e.typeKey.isBlank()) {
                o.typeKey(e.typeKey);
            }
            o.referenceLabel(e.referenceLabel);
            o.structuralObject(e.structuralObject);
            o.structuralPath(e.structuralPath);
            byKey.put(compositeKey(e.typeKey, e.qid), o);
        }
        Map<String, WikidataDynamicObject> byQidSingle = uniqueByQid(byKey);
        for (Entity e : snapshot.entities) {
            WikidataDynamicObject o = byKey.get(compositeKey(e.typeKey, e.qid));
            for (Map.Entry<String, Object> entry : e.fields.entrySet()) {
                o.dynamicFields().put(entry.getKey(),
                        decode(entry.getValue(), byKey, byQidSingle));
            }
            migrateLegacyGroupPath(o);
        }
        restoreLegacyGroupStructure(byKey.values());
        return byKey;
    }

    /**
     * Older manual-domain snapshots embedded one path-only ViewableGroup value
     * in every member's {@code groups} collection. Rebuild the shared structural
     * graph in memory: one group per full path, parent/children links, and
     * transitive members (the same semantics as DefaultViewableGroup.addMember).
     */
    private static void restoreLegacyGroupStructure(
            Collection<WikidataDynamicObject> owners) {
        Map<WikidataDynamicObject, List<WikidataDynamicObject>> refsByOwner =
                new LinkedHashMap<>();
        Map<List<String>, WikidataDynamicObject> groupsByPath =
                new LinkedHashMap<>();
        boolean hasLegacy = false;

        for (WikidataDynamicObject owner : owners) {
            List<WikidataDynamicObject> references =
                    structuralGroupValues(owner.get("groups"));
            if (references.isEmpty()) {
                continue;
            }
            refsByOwner.put(owner, references);
            for (WikidataDynamicObject group : references) {
                List<String> path = group.structuralPath();
                if (path.isEmpty()) {
                    continue;
                }
                if (isLegacyEmbeddedGroup(group)) {
                    hasLegacy = true;
                } else {
                    groupsByPath.putIfAbsent(path, group);
                }
            }
        }
        if (!hasLegacy) {
            return;
        }

        for (Map.Entry<WikidataDynamicObject,
                List<WikidataDynamicObject>> entry : refsByOwner.entrySet()) {
            WikidataDynamicObject owner = entry.getKey();
            List<WikidataDynamicObject> normalized = new ArrayList<>();
            for (WikidataDynamicObject reference : entry.getValue()) {
                List<String> path = reference.structuralPath();
                WikidataDynamicObject group = isLegacyEmbeddedGroup(reference)
                        && !path.isEmpty()
                        ? groupNode(path, groupsByPath) : reference;
                if (!normalized.contains(group)) {
                    normalized.add(group);
                }
                if (!path.isEmpty()) {
                    for (int size = 1; size <= path.size(); size++) {
                        addGroupLink(
                                groupNode(path.subList(0, size), groupsByPath),
                                "members", owner);
                    }
                }
            }
            owner.put("groups", normalized);
        }
    }

    private static boolean isLegacyEmbeddedGroup(
            WikidataDynamicObject group) {
        return group != null
                && "ViewableGroup".equals(group.typeName())
                && (group.isValueObject()
                || group.qid() == null || group.qid().isBlank());
    }

    private static List<WikidataDynamicObject> structuralGroupValues(
            Object value) {
        List<WikidataDynamicObject> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof WikidataDynamicObject group
                        && "ViewableGroup".equals(group.typeName())) {
                    result.add(group);
                }
            }
        } else if (value instanceof WikidataDynamicObject group
                && "ViewableGroup".equals(group.typeName())) {
            result.add(group);
        }
        return result;
    }

    private static WikidataDynamicObject groupNode(
            List<String> sourcePath,
            Map<List<String>, WikidataDynamicObject> groupsByPath) {
        List<String> path = List.copyOf(sourcePath);
        WikidataDynamicObject existing = groupsByPath.get(path);
        if (existing != null) {
            return existing;
        }

        String label = path.get(path.size() - 1);
        WikidataDynamicObject group = new WikidataDynamicObject(
                String.join(".", path), label);
        group.type("ViewableGroup");
        group.typeKey("ViewableGroup");
        group.structuralObject(true);
        group.structuralPath(path);
        group.put("children", new ArrayList<WikidataDynamicObject>());
        group.put("members", new ArrayList<WikidataDynamicObject>());
        groupsByPath.put(path, group);

        if (path.size() > 1) {
            WikidataDynamicObject parent = groupNode(
                    path.subList(0, path.size() - 1), groupsByPath);
            group.put("parent", parent);
            addGroupLink(parent, "children", group);
        }
        return group;
    }

    @SuppressWarnings("unchecked")
    private static void addGroupLink(
            WikidataDynamicObject group, String field,
            WikidataDynamicObject value) {
        Object current = group.get(field);
        List<WikidataDynamicObject> links =
                current instanceof List<?> list
                        ? (List<WikidataDynamicObject>) list
                        : new ArrayList<>();
        if (current != links) {
            group.put(field, links);
        }
        if (!links.contains(value)) {
            links.add(value);
        }
    }

    /** Map each qid to its entity ONLY when that qid has a single entity — the safe
     *  fallback for an untyped or pre-v4 ref/root whose ⟨type, qid⟩ doesn't match. */
    private static Map<String, WikidataDynamicObject> uniqueByQid(
            Map<String, WikidataDynamicObject> byKey) {
        Map<String, WikidataDynamicObject> single = new java.util.HashMap<>();
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (Map.Entry<String, WikidataDynamicObject> e : byKey.entrySet()) {
            String qid = qidPart(e.getKey());
            if (qid == null) {
                continue;
            }
            if (single.putIfAbsent(qid, e.getValue()) != null) {
                ambiguous.add(qid);
            }
        }
        ambiguous.forEach(single::remove);
        return single;
    }

    private static List<WikidataDynamicObject> resolveRoots(
            List<String> keys,
            Map<String, WikidataDynamicObject> byKey) {
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, WikidataDynamicObject> byQidSingle = uniqueByQid(byKey);
        List<WikidataDynamicObject> roots = new ArrayList<>();
        for (String key : keys) {
            WikidataDynamicObject value = byKey.get(key);
            if (value == null) {
                value = byQidSingle.get(qidPart(key));
            }
            if (value != null) {
                roots.add(value);
            }
        }
        return roots;
    }

    private Object decode(Object v, Map<String, WikidataDynamicObject> byKey,
            Map<String, WikidataDynamicObject> byQidSingle) {
        if (v instanceof Ref ref) {
            WikidataDynamicObject o = byKey.get(compositeKey(ref.type, ref.qid));
            // An untyped ref (or a pre-v4 ref with no type) may not match a stamped
            // entity's ⟨type, qid⟩ key — fall back to the unique entity for that qid.
            return o != null ? o : byQidSingle.get(ref.qid);
        }
        if (v instanceof Entity e) {
            return inlineWdo(e, byKey, byQidSingle);   // an inlined value object, not a ref
        }
        if (v instanceof DateVal d) {
            return aux.FlexibleDate.parse(d.date);
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) out.add(decode(e, byKey, byQidSingle));
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()),
                        decode(entry.getValue(), byKey, byQidSingle));
            }
            return out;
        }
        // Snapshots saved before typed dates hold the raw time literal as a
        // string; upgrade it on load (the probe only matches unambiguous
        // [+-]YYYY-MM-DDT… literals) so old domains need no regeneration.
        if (v instanceof String s) {
            aux.FlexibleDate date = aux.FlexibleDate.fromWikidataLiteral(s);
            if (date != null) {
                return date;
            }
        }
        return v;
    }

    /** A value object serialized in place: a nested {@link Entity} (no qid) with its
     *  fields recursively encoded (entity fields still become Refs). */
    private Entity inlineEntity(WikidataDynamicObject w) {
        Entity e = new Entity();
        e.qid = null;                       // a value has no identity
        e.name = w.getDisplayName();
        String referenceLabel = w.getReferenceLabel();
        if (referenceLabel != null
                && !referenceLabel.equals(w.getDisplayName())) {
            e.referenceLabel = referenceLabel;
        }
        e.type = w.typeName();              // still carried, for rendering
        e.structuralObject = w.isStructuralObject();
        if (!w.structuralPath().isEmpty()) {
            e.structuralPath = new ArrayList<>(w.structuralPath());
        }
        for (Map.Entry<String, Object> f : w.dynamicFields().entrySet()) {
            e.fields.put(f.getKey(), encode(f.getValue()));
        }
        return e;
    }

    /** Reconstruct an inlined value object (a field-value {@link Entity}, never a pool
     *  member) — a WDO with no qid, marked as a value, fields decoded recursively. */
    private WikidataDynamicObject inlineWdo(
            Entity e, Map<String, WikidataDynamicObject> byKey,
            Map<String, WikidataDynamicObject> byQidSingle) {
        WikidataDynamicObject o = new WikidataDynamicObject(null, e.name);
        if (e.type != null && !e.type.isBlank()) {
            o.type(e.type);
        }
        o.referenceLabel(e.referenceLabel);
        o.valueObject(true);
        o.structuralObject(e.structuralObject);
        o.structuralPath(e.structuralPath);
        for (Map.Entry<String, Object> entry : e.fields.entrySet()) {
            o.dynamicFields().put(entry.getKey(), decode(entry.getValue(), byKey, byQidSingle));
        }
        migrateLegacyGroupPath(o);
        return o;
    }

    /** Upgrades the first group-value format in memory: ancestry remains available
     *  to rebuild the tree but is no longer a visible dynamic card field. */
    private static void migrateLegacyGroupPath(WikidataDynamicObject object) {
        if (object == null || !"ViewableGroup".equals(object.typeName())) {
            return;
        }
        Object legacy = object.get("path");
        // Only legacy path-carriers are structural migration objects. A current
        // ordinary ViewableGroup has no path metadata and stays an ordinary entity.
        if (!(legacy instanceof java.util.Collection<?>)
                && object.structuralPath().isEmpty()) {
            return;
        }
        object.structuralObject(true);
        if (object.structuralPath().isEmpty()
                && legacy instanceof java.util.Collection<?> path) {
            object.structuralPath(path);
        }
        object.remove("path");
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

    /** A reference to another entity in the same snapshot, by ⟨type, qid⟩. {@code type}
     *  is the target's typeKey; blank/absent for an untyped target or a pre-v4 snapshot,
     *  in which case load falls back to the unique entity for the qid. */
    public static class Ref {
        public String type;
        public String qid;

        public Ref() {}

        public Ref(String type, String qid) {
            this.type = type;
            this.qid = qid;
        }
    }

    /** A typed date value, in {@link aux.FlexibleDate}'s self-describing
     *  string form ("1959", "1959-04-06", "500 BC"). */
    public static class DateVal {
        public String date;

        public DateVal() {}

        public DateVal(String date) {
            this.date = date;
        }
    }

    public static class Entity {
        public String qid;
        public String name;
        // Generic Viewable reference label when it differs from the display name.
        public String referenceLabel;
        // The stamped domain class (e.g. "Constellation", "Star"); null for an
        // untyped leaf reference. Lets one snapshot carry several classes.
        public String type;
        // The OBJECT-identity type key ⟨typeKey, qid⟩ (stable logical class name).
        // Null for pre-v4 snapshots and untyped leaves.
        public String typeKey;
        public boolean structuralObject;
        // Hidden authored hierarchy metadata for structural ViewableGroup
        // entities; never exposed as a dynamic card field.
        public List<String> structuralPath;
        public Map<String, Object> fields = new LinkedHashMap<>();
    }

    /** Flattened snapshot: a root-qid list plus a deduped entity pool. */
    public static class FlatSnapshot {
        public int version = FORMAT_VERSION;
        public List<String> roots = new ArrayList<>();
        public List<String> groupRoots = new ArrayList<>();
        public List<Entity> entities = new ArrayList<>();
        public SnapshotFieldGraph fieldGraph;
    }

    public record LoadedSnapshot(
            List<WikidataDynamicObject> objects,
            SnapshotFieldGraph fieldGraph,
            List<WikidataDynamicObject> memberRoots,
            List<WikidataDynamicObject> groupRoots) {}

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
