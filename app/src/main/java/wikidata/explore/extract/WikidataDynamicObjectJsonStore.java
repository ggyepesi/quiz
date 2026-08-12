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
 * <p>One qid maps to one object. Reference fields (e.g. a constellation's
 * neighbours, which point at other constellations) make the graph cyclic and
 * deep, so objects are <b>not</b> inlined recursively — that would loop forever
 * or blow Jackson's nesting-depth limit. Instead the graph is flattened: every
 * reachable object is written once into a pool keyed by qid, and object-valued
 * fields are stored as {@link Ref} markers (just a qid) and re-linked on load.
 *
 * <p>The reader accepts only the current flattened format. Older snapshots must be
 * regenerated from their source/model rather than translated during every load.
 */
public class WikidataDynamicObjectJsonStore {

    private static final int FORMAT_VERSION = 7;

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
        return o == null ? null : compositeKey(typePart(o), o.getIdentifier());
    }

    /** The qid part of a composite key. */
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
        return saveWithFieldGraph(objects, file, (DomainModel) null);
    }

    /**
     * Saves values and enriches the observed field graph with the producing
     * domain's declared schema. This preserves null fields and empty typed
     * collections without writing placeholder values into the instances.
     */
    public SnapshotFieldGraph saveWithFieldGraph(
            List<WikidataDynamicObject> objects, File file, DomainModel schema)
            throws IOException {
        return saveWithGroupRootBindings(objects, List.of(), file, schema);
    }

    /** Generated-domain counterpart using the exact model that produced the values. */
    public SnapshotFieldGraph saveWithFieldGraph(
            List<WikidataDynamicObject> objects,
            File file,
            wikidata.explore.model.GeneratedProjectModel schema)
            throws IOException {
        return saveWithGroupRootBindingsInternal(
                objects, List.of(), file,
                builder -> builder.declare(schema));
    }

    public SnapshotFieldGraph saveWithGroupRootBindings(
            List<WikidataDynamicObject> memberRoots,
            List<GroupRootBinding> groupRootBindings,
            File file,
            DomainModel schema)
            throws IOException {

        return saveWithGroupRootBindingsInternal(
                memberRoots, groupRootBindings, file,
                builder -> builder.declare(schema));
    }

    private SnapshotFieldGraph saveWithGroupRootBindingsInternal(
            List<WikidataDynamicObject> memberRoots,
            List<GroupRootBinding> groupRootBindings,
            File file,
            java.util.function.Consumer<SnapshotFieldGraph.Builder> declareSchema)
            throws IOException {

        if (memberRoots == null) memberRoots = List.of();
        if (groupRootBindings == null) groupRootBindings = List.of();

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
        for (GroupRootBinding binding : groupRootBindings) {
            if (binding == null || binding.root() == null
                    || binding.memberType() == null
                    || binding.memberType().isBlank()) {
                throw new IllegalArgumentException(
                        "Every group root requires an explicit member type");
            }
            collect(binding.root(), byQid, visited, fieldGraph);
        }
        if (declareSchema != null) {
            declareSchema.accept(fieldGraph);
        }
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
        for (GroupRootBinding binding : groupRootBindings) {
            WikidataDynamicObject o = binding.root();
            String k = poolKey(o);
            if (k != null && o.hasTypeStamp()) {
                snapshot.groupRoots.add(k);
                snapshot.groupRootBindings.add(
                        new GroupRootRef(binding.memberType(), k));
            }
        }
        // One qid can now yield SEVERAL entities — one per distinct real type — so a State
        // "France" and a ViewableGroup "France" stay separate ⟨type, qid⟩ objects instead
        // of merging. An untyped reference copy is absorbed into the single stamped entity.
        for (List<WikidataDynamicObject> instances : byQid.values()) {
            snapshot.entities.addAll(toEntities(instances, snapshot.fieldGraph));
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
    private List<Entity> toEntities(List<WikidataDynamicObject> instances,
                                    SnapshotFieldGraph graph) {
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
            out.add(toEntity(instances, type, graph));   // merge everything (untyped absorbed)
        } else {
            for (Map.Entry<String, List<WikidataDynamicObject>> t : byType.entrySet()) {
                out.add(toEntity(t.getValue(), t.getKey(), graph));
            }
            if (!untyped.isEmpty()) {
                out.add(toEntity(untyped, null, graph));
            }
        }
        return out;
    }

    // One entity DTO per ⟨typeKey, qid⟩, MERGING its instances: a resolved label
    // over the bare qid, a real class stamp over the untyped sentinel, and the
    // UNION of every field's values (so a rich carrier's `type` isn't lost to a
    // field-poor reference copy). Object-valued fields become ⟨type, qid⟩ refs.
    private Entity toEntity(List<WikidataDynamicObject> instances, String typeKey,
                            SnapshotFieldGraph graph) {
        WikidataDynamicObject first = instances.get(0);
        Entity e = new Entity();
        e.id = first.getIdentifier();
        e.typeKey = typeKey;
        e.name = first.getDisplayName();
        for (WikidataDynamicObject o : instances) {
            String n = o.getDisplayName();
            if (n != null && !n.equals(e.id)) { e.name = n; break; }
        }
        for (WikidataDynamicObject o : instances) {
            String label = o.getReferenceLabel();
            if (label != null && !label.isBlank()
                    && !label.equals(o.getDisplayName())) {
                e.referenceLabel = label;
                break;
            }
        }

        java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
        for (WikidataDynamicObject o : instances) {
            if (o.hasTypeStamp()) classes.addAll(o.directClassNames());
        }
        // A duplicate reference copy can still carry the old base stamp beside the
        // richer subtype carrier. Base membership is inherited, not a second direct claim.
        classes.removeIf(base -> classes.stream().anyMatch(candidate ->
                !candidate.equals(base) && isSubclassOf(candidate, base, graph)));
        for (WikidataDynamicObject o : instances) {
            o.fieldStatuses().forEach((field, status) ->
                    e.fieldStatus.putIfAbsent(field, status.stored()));
        }
        e.classes.addAll(classes);
        e.type = mostSpecificClass(classes, graph);
        if (e.type == null) {
            for (WikidataDynamicObject o : instances) {
                String t = o.typeName();
                if (t != null && !t.isBlank() && !"WikidataDynamicObject".equals(t)) {
                    e.type = t;
                    break;
                }
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
            return wa.getIdentifier() != null
                    && wa.getIdentifier().equals(wb.getIdentifier())
                    && java.util.Objects.equals(typePart(wa), typePart(wb));
        }
        return a != null && a.equals(b);
    }

    private Object encode(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            // A value object is serialized INLINE (a nested Entity) rather than a Ref to
            // a pooled entity — it has no identity and belongs to this parent.
            return w.isValueObject() ? inlineEntity(w) : new Ref(typePart(w), w.getIdentifier());
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
     * Loads instances and their persisted schema from one parse of a current snapshot.
     */
    public LoadedSnapshot loadAllWithFieldGraph(File file) throws IOException {
        JsonNode tree = mapper.readTree(file);
        if (tree == null || !tree.has("entities") || !tree.has("roots")
                || !tree.has("groupRoots") || !tree.has("groupRootBindings")
                || !tree.has("fieldGraph")) {
            throw new IOException("Unsupported snapshot format; regenerate " + file);
        }
        FlatSnapshot snapshot = mapper.treeToValue(tree, FlatSnapshot.class);
        if (snapshot == null || snapshot.version != FORMAT_VERSION
                || snapshot.fieldGraph == null
                || snapshot.fieldGraph.version != SnapshotFieldGraph.FORMAT_VERSION) {
            throw new IOException("Unsupported snapshot version; regenerate " + file);
        }
        Map<String, WikidataDynamicObject> entities = buildEntities(snapshot);
        List<WikidataDynamicObject> objects = new ArrayList<>(entities.values());
        attachSchemas(objects, snapshot.fieldGraph);
        List<WikidataDynamicObject> groups = resolveRoots(
                snapshot.groupRoots, entities);
        return new LoadedSnapshot(objects, snapshot.fieldGraph,
                resolveRoots(snapshot.roots, entities), groups,
                resolveGroupRootBindings(snapshot, entities));
    }

    private static List<LoadedGroupRoot> resolveGroupRootBindings(
            FlatSnapshot snapshot,
            Map<String, WikidataDynamicObject> entities) throws IOException {
        List<LoadedGroupRoot> result = new ArrayList<>();
        for (GroupRootRef ref : snapshot.groupRootBindings) {
            if (ref == null || ref.memberType == null || ref.memberType.isBlank()) {
                throw new IOException("Snapshot group root has no member type");
            }
            WikidataDynamicObject root = entities.get(ref.root);
            if (root == null) throw new IOException(
                    "Snapshot group root is missing from entity pool: " + ref.root);
            result.add(new LoadedGroupRoot(ref.memberType, root));
        }
        return List.copyOf(result);
    }

    private static void attachSchemas(
            Collection<WikidataDynamicObject> objects,
            SnapshotFieldGraph graph) {
        if (objects == null || graph == null) {
            return;
        }
        for (WikidataDynamicObject object : objects) {
            String className = mostSpecificClass(
                    object.directClassNames(), graph);
            if (className == null) className = object.typeName();
            object.dynamicFieldSchema(
                    graph.fieldSchema(className, Set.of()));
        }
    }

    private static String mostSpecificClass(
            Collection<String> classes, SnapshotFieldGraph graph) {
        String best = null;
        int bestDepth = -1;
        if (classes == null) return null;
        for (String candidate : classes) {
            if (candidate == null || candidate.isBlank()) continue;
            int depth = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String current = candidate; current != null && seen.add(current);
                 current = graph == null ? null : graph.baseType(current)) {
                depth++;
            }
            if (depth > bestDepth) {
                best = candidate;
                bestDepth = depth;
            }
        }
        return best;
    }

    private static boolean isSubclassOf(
            String candidate, String expected, SnapshotFieldGraph graph) {
        if (candidate == null || expected == null || graph == null) return false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String current = candidate; current != null && seen.add(current);
             current = graph.baseType(current)) {
            if (expected.equals(current)) return true;
        }
        return false;
    }


    private Map<String, WikidataDynamicObject> buildEntities(FlatSnapshot snapshot) {
        // Build shells first so refs (including cycles) resolve to one instance per
        // ⟨type, qid⟩; carry the persisted type + typeKey so multi-class snapshots and the
        // identity split round-trip.
        Map<String, WikidataDynamicObject> byKey = new LinkedHashMap<>();
        for (Entity e : snapshot.entities) {
            WikidataDynamicObject o = new WikidataDynamicObject(e.id, e.name);
            if (e.type != null && !e.type.isBlank()) {
                o.type(e.type);
            }
            o.directClasses(e.classes);
            String concreteType = mostSpecificClass(e.classes, snapshot.fieldGraph);
            if (concreteType != null) o.type(concreteType);
            if (e.typeKey != null && !e.typeKey.isBlank()) {
                o.typeKey(e.typeKey);
            }
            o.referenceLabel(e.referenceLabel);
            e.fieldStatus.forEach((field, token) -> {
                FieldStatus status = FieldStatus.fromStored(token);
                if (status != null) o.fieldStatus(field, status);
            });
            byKey.put(compositeKey(e.typeKey, e.id), o);
        }
        Map<String, WikidataDynamicObject> byQidSingle = uniqueByQid(byKey);
        for (Entity e : snapshot.entities) {
            WikidataDynamicObject o = byKey.get(compositeKey(e.typeKey, e.id));
            for (Map.Entry<String, Object> entry : e.fields.entrySet()) {
                o.dynamicFields().put(entry.getKey(),
                        decode(entry.getValue(), byKey, byQidSingle));
            }
        }
        return byKey;
    }

    /** Map each qid to its entity only when that qid has a single entity. This resolves
     *  current-format untyped reference copies without confusing cross-type namesakes. */
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
            Map<String, WikidataDynamicObject> byKey) throws IOException {
        if (keys == null || keys.isEmpty()) {
            return new ArrayList<>();
        }
        List<WikidataDynamicObject> roots = new ArrayList<>();
        for (String key : keys) {
            WikidataDynamicObject value = byKey.get(key);
            if (value == null) throw new IOException(
                    "Snapshot root is missing from entity pool: " + key);
            roots.add(value);
        }
        return roots;
    }

    private Object decode(Object v, Map<String, WikidataDynamicObject> byKey,
            Map<String, WikidataDynamicObject> byQidSingle) {
        if (v instanceof Ref ref) {
            WikidataDynamicObject o = byKey.get(compositeKey(ref.type, ref.id));
            // An untyped ref may not match a stamped entity's ⟨type, qid⟩ key;
            // resolve it only when the qid identifies one entity unambiguously.
            return o != null ? o : byQidSingle.get(ref.id);
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
        return v;
    }

    /** A value object serialized in place: a nested {@link Entity} (no qid) with its
     *  fields recursively encoded (entity fields still become Refs). */
    private Entity inlineEntity(WikidataDynamicObject w) {
        Entity e = new Entity();
        e.id = null;                       // a value has no identity
        e.name = w.getDisplayName();
        String referenceLabel = w.getReferenceLabel();
        if (referenceLabel != null
                && !referenceLabel.equals(w.getDisplayName())) {
            e.referenceLabel = referenceLabel;
        }
        e.type = w.typeName();              // still carried, for rendering
        e.classes.addAll(w.directClassNames());
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
        o.directClasses(e.classes);
        o.referenceLabel(e.referenceLabel);
        o.valueObject(true);
        for (Map.Entry<String, Object> entry : e.fields.entrySet()) {
            o.dynamicFields().put(entry.getKey(), decode(entry.getValue(), byKey, byQidSingle));
        }
        return o;
    }

    private static String keyOf(WikidataDynamicObject o) {
        if (o == null) return null;
        String qid = o.getIdentifier();
        return qid == null || qid.isBlank() ? null : qid;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ------------------------------------------------------------------
    // On-disk shapes
    // ------------------------------------------------------------------

    /** A reference to another entity in the same snapshot, by ⟨type, qid⟩. {@code type}
     *  is blank only for a current-format untyped target, resolved by unique qid. */
    public static class Ref {
        public String type;
        public String id;

        public Ref() {}

        public Ref(String type, String qid) {
            this.type = type;
            this.id = qid;
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
        public String id;
        public String name;
        // Generic Viewable reference label when it differs from the display name.
        public String referenceLabel;
        // The stamped domain class (e.g. "Constellation", "Star"); null for an
        // untyped leaf reference. Lets one snapshot carry several classes.
        public String type;
        // Direct semantic class claims. Base-class membership is derived from the
        // persisted class hierarchy in fieldGraph.
        public List<String> classes = new ArrayList<>();
        // The OBJECT-identity type key ⟨typeKey, qid⟩ (stable logical class name).
        // Null for untyped leaves.
        public String typeKey;
        public Map<String, Object> fields = new LinkedHashMap<>();
        // Why a field is empty when the SOURCE said so — "unknown" / "none". Absent for
        // the ordinary case, so an existing snapshot loads unchanged.
        public Map<String, String> fieldStatus = new LinkedHashMap<>();
    }

    /** Flattened snapshot: a root-qid list plus a deduped entity pool. */
    public static class FlatSnapshot {
        public int version = FORMAT_VERSION;
        public List<String> roots = new ArrayList<>();
        public List<String> groupRoots = new ArrayList<>();
        public List<GroupRootRef> groupRootBindings = new ArrayList<>();
        public List<Entity> entities = new ArrayList<>();
        public SnapshotFieldGraph fieldGraph;
    }

    public record LoadedSnapshot(
            List<WikidataDynamicObject> objects,
            SnapshotFieldGraph fieldGraph,
            List<WikidataDynamicObject> memberRoots,
            List<WikidataDynamicObject> groupRoots,
            List<LoadedGroupRoot> groupRootBindings) {}

    public record GroupRootBinding(String memberType, WikidataDynamicObject root) {}
    public record LoadedGroupRoot(String memberType, WikidataDynamicObject root) {}

    public static class GroupRootRef {
        public String memberType;
        public String root;

        public GroupRootRef() {}
        public GroupRootRef(String memberType, String root) {
            this.memberType = memberType;
            this.root = root;
        }
    }

}
