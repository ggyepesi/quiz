package quiz.web;

import objectview.Viewable;
import objectview.group.ViewableGroup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of {@link ViewableSource}s. Loads each source lazily on first
 * access and indexes its instances — <i>and every Viewable they reference</i>
 * — by {@code (type, id)}, so the HTTP layer can resolve a reference the
 * client asks to expand even when the referenced type has no source of its
 * own (e.g. the {@code WikidataEntity}s an {@code OscarNomination} points at).
 */
public class ViewableStore {

    private final Map<String, ViewableSource> sources = new LinkedHashMap<>();
    private final Set<String> loadedTypes = new HashSet<>();

    private final Map<String, Viewable> index = new LinkedHashMap<>();
    private final Map<String, List<Viewable>> topByType = new LinkedHashMap<>();
    private final Map<String, ViewableGroup<?>> rootGroupByType = new LinkedHashMap<>();

    private final Map<String, String> originByType = new LinkedHashMap<>();

    public void register(ViewableSource source) {
        register(source, "");
    }

    /**
     * Registers one type's source. A type name is what a URL asks for and what the
     * client lists, so it resolves to ONE source: registering a second silently replaced
     * the first, and browsing a type then showed whichever domain the registry happened
     * to load last, under a heading claiming the other. Oscars' 6,863 people were served
     * as History's 142 that way, with nothing said.
     *
     * <p>The conflict is refused here, where the invariant is, and named for whoever
     * composes the store — two domains declaring one served type is a modelling decision
     * to make, not a condition to recover from.
     */
    public void register(ViewableSource source, String origin) {
        if (source == null) {
            return;
        }
        String type = source.type();
        ViewableSource existing = sources.get(type);
        if (existing != null && existing != source) {
            throw new IllegalStateException("Two sources serve the type '" + type + "': "
                    + described(originByType.get(type)) + " and " + described(origin)
                    + ". A served type name resolves to one source.");
        }
        sources.put(type, source);
        originByType.put(type, origin == null ? "" : origin);
    }

    private static String described(String origin) {
        return origin == null || origin.isBlank() ? "an unnamed source" : "'" + origin + "'";
    }

    public List<String> types() {
        return new ArrayList<>(sources.keySet());
    }

    public synchronized Collection<Viewable> list(String type) throws Exception {
        if (!sources.containsKey(type)) {
            return null;
        }
        ensureLoaded(type);
        return topByType.getOrDefault(type, List.of());
    }

    public synchronized Viewable get(String type, String id) throws Exception {
        // A registered source owns its type. Load it before consulting references
        // indexed while another type was loaded; otherwise request order decides which
        // domain's copy of Person wins.
        ensureLoaded(type);
        return index.get(key(type, id));
    }

    private void ensureLoaded(String type) throws Exception {
        if (!loadedTypes.add(type)) {
            return;
        }

        ViewableSource source = sources.get(type);
        if (source == null) {
            return;
        }

        List<Viewable> top = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Viewable q : source.load()) {
            if (q != null) {
                top.add(q);
                indexTopLevel(q, visited);
            }
        }

        topByType.put(type, top);

        ViewableGroup<?> root = source.rootGroup();
        if (root != null) {
            rootGroupByType.put(type, root);
        }
    }

    /** The group hierarchy root for a type, or null if it has no groups. */
    public synchronized ViewableGroup<?> rootGroup(String type) throws Exception {
        ensureLoaded(type);
        return rootGroupByType.get(type);
    }

    /** The declared groupable dimensions for a type (empty for an unknown type or a
     *  source that declares none) — served for the client's live re-faceting. */
    public synchronized List<quiz.web.sources.Dimension> dimensions(String type)
            throws Exception {
        ViewableSource s = sources.get(type);
        return s == null ? List.of() : s.dimensions();
    }

    /** Per-field coverage (present/missing) for a type — the first consistency check. */
    public synchronized List<quiz.web.sources.Coverage.FieldCoverage> coverage(String type)
            throws Exception {
        ViewableSource s = sources.get(type);
        return s == null ? List.of() : s.coverage();
    }

    /** The members of a type MISSING a value at {@code path} (up to {@code limit}) —
     *  the drill-down worklist behind a coverage gap. Each carries id/name/type so the
     *  UI can link to Wikidata (a Q-id id) and, next, query other sources for a fill. */
    public synchronized List<ViewableView.Ref> missing(String type, String path, int limit)
            throws Exception {
        Collection<Viewable> all = list(type);
        if (all == null || path == null || path.isBlank()) {
            return List.of();
        }
        List<ViewableView.Ref> out = new ArrayList<>();
        for (Viewable q : all) {
            if (!quiz.web.sources.Coverage.hasValue(q, path)) {
                out.add(new ViewableView.Ref(
                        q.getIdentifier(), q.getDisplayName(), q.typeName(),
                        ViewableJson.thumbUrl(q), null));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** The group's explicit members, or the whole type if no group is selected. */
    public synchronized Collection<Viewable> members(String type, String groupFullName) throws Exception {
        if (groupFullName == null || groupFullName.isBlank()) {
            return list(type);
        }

        ViewableGroup<?> root = rootGroup(type);
        ViewableGroup<?> group = root == null ? null : findGroup(root, groupFullName);
        if (group == null) {
            return list(type);
        }

        return group.getMembers().stream()
                .filter(java.util.Objects::nonNull)
                .map(Viewable.class::cast)
                .toList();
    }

    private static ViewableGroup<?> findGroup(
            ViewableGroup<?> g, String fullName) {
        if (fullName.equals(g.getFullName())) {
            return g;
        }
        for (ViewableGroup<?> c : g.getChildren()) {
            ViewableGroup<?> found = findGroup(c, fullName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void indexTopLevel(Viewable q, Set<Object> visited) {
        if (q == null || !visited.add(q)) {
            return;
        }

        // Unlike a merely reachable reference, this value came from the source
        // registered for its type and is therefore authoritative for that address.
        index.put(key(q.typeName(), q.getIdentifier()), q);
        for (Viewable child : children(q)) {
            indexReachable(child, visited);
        }
    }

    /** Index q and, recursively, every Viewable reachable through its fields. */
    private void indexReachable(Viewable q, Set<Object> visited) {
        if (q == null || !visited.add(q)) {
            return;
        }

        index.putIfAbsent(key(q.typeName(), q.getIdentifier()), q);

        for (Viewable child : children(q)) {
            indexReachable(child, visited);
        }
    }

    private static List<Viewable> children(Viewable q) {
        List<Viewable> out = new ArrayList<>();
        objectview.field.FieldSet fields = objectview.field.FieldSet.of(q);
        for (objectview.field.FieldRef field : fields.fields()) {
            addViewables(fields.read(field.name()), out);
        }
        return out;
    }

    private static void addViewables(Object v, List<Viewable> out) {
        if (v instanceof Viewable c) {
            out.add(c);
        } else if (v instanceof Collection<?> col) {
            for (Object i : col) {
                if (i instanceof Viewable c) {
                    out.add(c);
                }
            }
        } else if (v instanceof Map<?, ?> m) {
            for (Object i : m.values()) {
                if (i instanceof Viewable c) {
                    out.add(c);
                }
            }
        }
    }

    private static String key(String type, String id) {
        return type + '\0' + id;
    }
}
