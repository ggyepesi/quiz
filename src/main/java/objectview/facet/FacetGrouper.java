package objectview.facet;

import objectview.MutableViewableGroup;
import objectview.Viewable;
import objectview.ViewableGroup.Role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds (or tags) a group tree from members and facet declarations, replacing
 * per-dataset hand-built trees. Generic in the member type {@code T} and the concrete
 * group type {@code G}, so it never names a host's concrete group: a host passes its
 * own {@code newRoot} factory (e.g. {@code QuizableGroup::new}) and gets that type back.
 *
 * <p>Shape: {@code root (universe, all members) -> facet node (a dimension, no direct
 * members) -> bucket (one facet value, its members)}. Roles ({@link Role}) are tagged
 * so the UI can treat facet nodes as non-selectable headers and buckets/the universe as
 * quiz scopes. Methods that CREATE a root take {@code newRoot}; methods that graft onto
 * an existing group don't (a group self-creates its children).
 */
public final class FacetGrouper {

    private FacetGrouper() {}

    /** Build a fresh faceted tree from members. */
    public static <T extends Viewable, G extends MutableViewableGroup<T, G>> G group(
            Function<String, G> newRoot,
            String rootName,
            Collection<? extends T> members,
            List<Facet> facets) {

        G root = newRoot.apply(rootName).role(Role.UNIVERSE);
        for (T m : members) {
            if (m != null) {
                root.addMember(m);
            }
        }
        return addFacets(root, members, facets);
    }

    /** Build a NESTED drill-down tree: the facets apply in order, each partitioning
     *  the buckets produced by the previous. */
    public static <T extends Viewable, G extends MutableViewableGroup<T, G>> G groupNested(
            Function<String, G> newRoot,
            String rootName,
            Collection<? extends T> members,
            List<Facet> facets) {

        G root = newRoot.apply(rootName).role(Role.UNIVERSE);
        for (T m : members) {
            if (m != null) {
                root.addMember(m);
            }
        }
        nest(root, members, facets, 0);
        return root;
    }

    /** Graft one nested drill-down CHAIN of facets onto an existing root. */
    public static <T extends Viewable, G extends MutableViewableGroup<T, G>> void graftNested(
            G root,
            Collection<? extends T> members,
            List<Facet> chain) {
        nest(root, members, chain, 0);
    }

    /** Graft a TREE of facet dimensions onto an existing {@code parent}. */
    public static <T extends Viewable, G extends MutableViewableGroup<T, G>> void graftTree(
            G parent,
            Collection<? extends T> members,
            List<FacetTree> dims) {
        if (dims == null) {
            return;
        }
        for (FacetTree dim : dims) {
            Facet facet = dim.facet();
            G facetNode = parent.getOrCreateChild(facet.label()).role(Role.FACET);

            Map<String, G> buckets = new LinkedHashMap<>();
            Map<String, List<T>> bucketMembers = new LinkedHashMap<>();
            for (T m : members) {
                if (m == null) {
                    continue;
                }
                for (FacetKey key : facet.keys().apply(m)) {
                    if (key == null || !key.isUsable()) {
                        continue;
                    }
                    G bucket = facetNode.getOrCreateChild(key.name()).role(Role.BUCKET);
                    if (key.ref() != null) {
                        bucket.keyRef(key.ref());
                    }
                    bucket.addMember(m);
                    buckets.putIfAbsent(key.name(), bucket);
                    bucketMembers.computeIfAbsent(key.name(), k -> new ArrayList<>()).add(m);
                }
            }
            for (Map.Entry<String, G> e : buckets.entrySet()) {
                graftTree(e.getValue(), bucketMembers.get(e.getKey()), dim.children());
            }
        }
    }

    // Partition members by facets[depth] under parent, then recurse into each bucket.
    private static <T extends Viewable, G extends MutableViewableGroup<T, G>> void nest(
            G parent,
            Collection<? extends T> members,
            List<Facet> facets,
            int depth) {
        if (facets == null || depth >= facets.size()) {
            return;
        }
        Facet facet = facets.get(depth);
        G facetNode = parent.getOrCreateChild(facet.label()).role(Role.FACET);

        Map<String, G> buckets = new LinkedHashMap<>();
        Map<String, List<T>> bucketMembers = new LinkedHashMap<>();
        for (T m : members) {
            if (m == null) {
                continue;
            }
            for (FacetKey key : facet.keys().apply(m)) {
                if (key == null || !key.isUsable()) {
                    continue;
                }
                G bucket = facetNode.getOrCreateChild(key.name()).role(Role.BUCKET);
                if (key.ref() != null) {
                    bucket.keyRef(key.ref());
                }
                bucket.addMember(m);
                buckets.putIfAbsent(key.name(), bucket);
                bucketMembers.computeIfAbsent(key.name(), k -> new ArrayList<>()).add(m);
            }
        }
        for (Map.Entry<String, G> e : buckets.entrySet()) {
            nest(e.getValue(), bucketMembers.get(e.getKey()), facets, depth + 1);
        }
    }

    /** Graft facet dimensions onto an existing root. Each facet becomes a FACET child;
     *  its values become BUCKETs. Reference buckets carry their key entity. */
    public static <T extends Viewable, G extends MutableViewableGroup<T, G>> G addFacets(
            G root,
            Collection<? extends T> members,
            List<Facet> facets) {

        for (Facet facet : facets) {
            G facetNode = root.getOrCreateChild(facet.label()).role(Role.FACET);

            for (T m : members) {
                if (m == null) {
                    continue;
                }
                for (FacetKey key : facet.keys().apply(m)) {
                    if (key == null || !key.isUsable()) {
                        continue;
                    }
                    G bucket = facetNode.getOrCreateChild(key.name()).role(Role.BUCKET);
                    if (key.ref() != null) {
                        bucket.keyRef(key.ref());
                    }
                    bucket.addMember(m);
                }
            }
        }
        return root;
    }

    /** Tag an existing (hand-built) tree by structure: root=UNIVERSE, internal
     *  nodes=FACET headers, leaves=BUCKETs. */
    public static <T extends Viewable, G extends MutableViewableGroup<T, G>> G assignRoles(G root) {
        assignRoles(root, 0);
        return root;
    }

    private static <T extends Viewable, G extends MutableViewableGroup<T, G>> void assignRoles(
            G g, int depth) {
        boolean leaf = g.getChildren().isEmpty();
        g.role(depth == 0 ? Role.UNIVERSE : leaf ? Role.BUCKET : Role.FACET);
        for (G c : g.getChildren()) {
            assignRoles(c, depth + 1);
        }
    }

    /** Re-parent a root's direct children under one named FACET node, then tag roles. */
    public static <T extends Viewable, G extends MutableViewableGroup<T, G>> G wrapChildrenAsFacet(
            G root, String facetLabel) {
        List<G> kids = new ArrayList<>(root.getChildren());
        G facet = root.getOrCreateChild(facetLabel);
        for (G k : kids) {
            facet.addChild(k);
            root.getChildrenMap().remove(k.getIdentifier());
        }
        return assignRoles(root);
    }
}
