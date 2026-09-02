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

    /**
     * What a served collection is called: the domain that owns it and the type name
     * within it.
     *
     * <p>A type name alone stopped identifying anything once domains began sharing
     * configuration. Nobel, Oscars and History all serve Person because they all import
     * the same Person model — that is the point of importing it, not a mistake to
     * detect. What they do not share is the instances: each domain owns its own, so the
     * domain is the missing half of the address rather than a label on it.
     */
    public record Address(String domain, String type) {
        public Address {
            domain = domain == null ? "" : domain.trim();
            type = type == null ? "" : type.trim();
        }

        /** How the address travels: {@code NobelPrizes:Person}. Not a slash — the
         *  detail route is {@code /api/viewable/{type}/{id}}, and a type carrying a
         *  slash silently became a domain plus an id that started with the type. */
        public static final char SEPARATOR = ':';

        @Override public String toString() {
            return domain.isBlank() ? type : domain + SEPARATOR + type;
        }
    }

    private final Map<Address, ViewableSource> sources = new LinkedHashMap<>();
    private final Set<Address> loadedTypes = new HashSet<>();

    private final Map<String, Viewable> index = new LinkedHashMap<>();
    private final Map<Address, List<Viewable>> topByType = new LinkedHashMap<>();
    private final Map<Address, ViewableGroup<?>> rootGroupByType = new LinkedHashMap<>();

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
        Address address = new Address(origin, source.type());
        ViewableSource existing = sources.get(address);
        if (existing != null && existing != source) {
            throw new IllegalStateException("Two sources serve " + address
                    + ". One domain serves each type once.");
        }
        sources.put(address, source);
    }

    /** Every served collection, in registration order. */
    public List<Address> addresses() {
        return new ArrayList<>(sources.keySet());
    }

    /** The distinct served type names. Several domains may share one. */
    public List<String> types() {
        List<String> out = new ArrayList<>();
        for (Address a : sources.keySet()) {
            if (!out.contains(a.type())) out.add(a.type());
        }
        return out;
    }

    /**
     * The one address for a bare type name, for a caller that has not said which domain
     * it means. Null when nothing serves the name.
     *
     * @throws IllegalStateException when more than one domain serves it — the caller has
     *         to say which, and the message names the choices rather than picking one.
     *         Serving whichever loaded last is the bug this replaced.
     */
    public Address resolve(String type) {
        List<Address> matches = new ArrayList<>();
        for (Address a : sources.keySet()) {
            if (a.type().equals(type)) matches.add(a);
        }
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) {
            List<String> domains = matches.stream().map(Address::domain).toList();
            throw new IllegalStateException("The type '" + type + "' is served by "
                    + String.join(", ", domains) + ". Ask for one of them by domain.");
        }
        return matches.getFirst();
    }

    public synchronized Collection<Viewable> list(String type) throws Exception {
        Address address = resolve(type);
        return address == null ? null : list(address);
    }

    public synchronized Collection<Viewable> list(Address address) throws Exception {
        if (address == null || !sources.containsKey(address)) {
            return null;
        }
        ensureLoaded(address);
        return topByType.getOrDefault(address, List.of());
    }

    public synchronized Viewable get(String type, String id) throws Exception {
        Address address = resolve(type);
        return address == null ? null : get(address, id);
    }

    public synchronized Viewable get(Address address, String id) throws Exception {
        if (address == null) return null;
        // A registered source owns its address. Load it before consulting references
        // indexed while another was loaded — request order used to decide which domain's
        // copy of Person won, which is what the domain in the key now settles.
        ensureLoaded(address);
        return index.get(key(address, id));
    }

    private void ensureLoaded(Address address) throws Exception {
        if (!loadedTypes.add(address)) {
            return;
        }

        ViewableSource source = sources.get(address);
        if (source == null) {
            return;
        }

        List<Viewable> top = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Viewable q : source.load()) {
            if (q != null) {
                top.add(q);
                indexTopLevel(address.domain(), q, visited);
            }
        }

        topByType.put(address, top);

        ViewableGroup<?> root = source.rootGroup();
        if (root != null) {
            rootGroupByType.put(address, root);
        }
    }

    /** The group hierarchy root for a type, or null if it has no groups. */
    public synchronized ViewableGroup<?> rootGroup(String type) throws Exception {
        Address address = resolve(type);
        return address == null ? null : rootGroup(address);
    }

    public synchronized ViewableGroup<?> rootGroup(Address address) throws Exception {
        if (address == null) return null;
        ensureLoaded(address);
        return rootGroupByType.get(address);
    }

    /** The declared groupable dimensions for a type (empty for an unknown type or a
     *  source that declares none) — served for the client's live re-faceting. */
    public synchronized List<quiz.web.sources.Dimension> dimensions(String type)
            throws Exception {
        return dimensions(resolve(type));
    }

    public synchronized List<quiz.web.sources.Dimension> dimensions(Address address)
            throws Exception {
        ViewableSource s = address == null ? null : sources.get(address);
        return s == null ? List.of() : s.dimensions();
    }

    /** Per-field coverage (present/missing) for a type — the first consistency check. */
    public synchronized List<quiz.web.sources.Coverage.FieldCoverage> coverage(String type)
            throws Exception {
        return coverage(resolve(type));
    }

    public synchronized List<quiz.web.sources.Coverage.FieldCoverage> coverage(
            Address address) throws Exception {
        ViewableSource s = address == null ? null : sources.get(address);
        return s == null ? List.of() : s.coverage();
    }

    /** The members of a type MISSING a value at {@code path} (up to {@code limit}) —
     *  the drill-down worklist behind a coverage gap. Each carries id/name/type so the
     *  UI can link to Wikidata (a Q-id id) and, next, query other sources for a fill. */
    public synchronized List<ViewableView.Ref> missing(String type, String path, int limit)
            throws Exception {
        return missing(resolve(type), path, limit);
    }

    public synchronized List<ViewableView.Ref> missing(
            Address address, String path, int limit) throws Exception {
        Collection<Viewable> all = list(address);
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
    public synchronized Collection<Viewable> members(String type, String groupFullName)
            throws Exception {
        return members(resolve(type), groupFullName);
    }

    public synchronized Collection<Viewable> members(
            Address address, String groupFullName) throws Exception {
        if (groupFullName == null || groupFullName.isBlank()) {
            return list(address);
        }

        ViewableGroup<?> root = rootGroup(address);
        ViewableGroup<?> group = root == null ? null : findGroup(root, groupFullName);
        if (group == null) {
            return list(address);
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

    private void indexTopLevel(String domain, Viewable q, Set<Object> visited) {
        if (q == null || !visited.add(q)) {
            return;
        }

        // Unlike a merely reachable reference, this value came from the source
        // registered for its address and is therefore authoritative for it.
        index.put(key(new Address(domain, q.typeName()), q.getIdentifier()), q);
        for (Viewable child : children(q)) {
            indexReachable(domain, child, visited);
        }
    }

    /** Index q and, recursively, every Viewable reachable through its fields. */
    private void indexReachable(String domain, Viewable q, Set<Object> visited) {
        if (q == null || !visited.add(q)) {
            return;
        }

        // Reached from a value this domain owns, so it is this domain's: a domain owns
        // its instances, and nothing references across domains.
        index.putIfAbsent(key(new Address(domain, q.typeName()), q.getIdentifier()), q);

        for (Viewable child : children(q)) {
            indexReachable(domain, child, visited);
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

    private static String key(Address address, String id) {
        return address.domain() + '\0' + address.type() + '\0' + id;
    }
}
