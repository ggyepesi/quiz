package objectview.facet;

import quiz.Quizable;
import objectview.QuizableGroup;
import objectview.QuizableGroup.Role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds (or tags) a {@link QuizableGroup} tree from members and facet
 * declarations, replacing per-dataset hand-built trees.
 *
 * <p>Shape: {@code root (universe, all members) -> facet node (a dimension,
 * no direct members) -> bucket (one facet value, its members)}. Every facet
 * is consistent — no "buckets directly under root" special case. Roles
 * ({@link Role}) are tagged so the UI can treat facet nodes as non-selectable
 * headers and only buckets/the universe as quiz scopes.
 */
public final class FacetGrouper {

    private FacetGrouper() {}

    /** Build a fresh faceted tree from members. */
    public static QuizableGroup group(
            String rootName,
            Collection<? extends Quizable> members,
            List<Facet> facets) {

        QuizableGroup root = new QuizableGroup(rootName).role(Role.UNIVERSE);
        for (Quizable m : members) {
            if (m != null) {
                root.addMember(m);
            }
        }
        return addFacets(root, members, facets);
    }

    /**
     * Build a NESTED drill-down tree: the facets apply in order, each one
     * partitioning the buckets produced by the previous — e.g. {@code [category,
     * year]} yields {@code root -> "by category" -> "Best Actress" -> "by decade"
     * -> "1980s" -> the nominations}. This is the hierarchical counterpart to
     * {@link #group} (which lays every facet out as a flat, parallel dimension off
     * the root). Use it when the facet ORDER is meaningful (a declared drill-down).
     */
    public static QuizableGroup groupNested(
            String rootName,
            Collection<? extends Quizable> members,
            List<Facet> facets) {

        QuizableGroup root = new QuizableGroup(rootName).role(Role.UNIVERSE);
        for (Quizable m : members) {
            if (m != null) {
                root.addMember(m);
            }
        }
        nest(root, members, facets, 0);
        return root;
    }

    /** Graft one nested drill-down CHAIN of facets onto an existing root (its first
     *  facet becomes a FACET child of root, the rest drill down). Call once per
     *  chain to lay several parallel dimensions off the same root. */
    public static void graftNested(QuizableGroup root,
                                   Collection<? extends Quizable> members,
                                   List<Facet> chain) {
        nest(root, members, chain, 0);
    }

    /**
     * Graft a TREE of facet dimensions onto an existing {@code parent}. Each
     * top-level {@link FacetTree} becomes a FACET child of {@code parent}; a node's
     * children are grafted <em>within each of its buckets</em> — so sibling children
     * are parallel sub-dimensions and a lone child is a nested drill-down. This is
     * the general form of {@link #graftNested} (a linear chain is the degenerate
     * tree where every node has one child).
     */
    public static void graftTree(QuizableGroup parent,
                                 Collection<? extends Quizable> members,
                                 List<FacetTree> dims) {
        if (dims == null) {
            return;
        }
        for (FacetTree dim : dims) {
            Facet facet = dim.facet();
            QuizableGroup facetNode = parent.getOrCreateChild(facet.label()).role(Role.FACET);

            Map<String, QuizableGroup> buckets = new LinkedHashMap<>();
            Map<String, List<Quizable>> bucketMembers = new LinkedHashMap<>();
            for (Quizable m : members) {
                if (m == null) {
                    continue;
                }
                for (FacetKey key : facet.keys().apply(m)) {
                    if (key == null || !key.isUsable()) {
                        continue;
                    }
                    QuizableGroup bucket =
                            facetNode.getOrCreateChild(key.name()).role(Role.BUCKET);
                    if (key.ref() != null) {
                        bucket.keyRef(key.ref());
                    }
                    bucket.addMember(m);
                    buckets.putIfAbsent(key.name(), bucket);
                    bucketMembers.computeIfAbsent(key.name(), k -> new ArrayList<>()).add(m);
                }
            }
            for (Map.Entry<String, QuizableGroup> e : buckets.entrySet()) {
                graftTree(e.getValue(), bucketMembers.get(e.getKey()), dim.children());
            }
        }
    }

    // Partition `members` by facets[depth] under `parent`, then recurse into each
    // resulting bucket with the next facet. addMember bubbles to ancestors, so the
    // universe + every intermediate bucket still hold the full union below them.
    private static void nest(QuizableGroup parent,
                             Collection<? extends Quizable> members,
                             List<Facet> facets, int depth) {
        if (facets == null || depth >= facets.size()) {
            return;
        }
        Facet facet = facets.get(depth);
        QuizableGroup facetNode = parent.getOrCreateChild(facet.label()).role(Role.FACET);

        Map<String, QuizableGroup> buckets = new LinkedHashMap<>();
        Map<String, List<Quizable>> bucketMembers = new LinkedHashMap<>();
        for (Quizable m : members) {
            if (m == null) {
                continue;
            }
            for (FacetKey key : facet.keys().apply(m)) {
                if (key == null || !key.isUsable()) {
                    continue;
                }
                QuizableGroup bucket =
                        facetNode.getOrCreateChild(key.name()).role(Role.BUCKET);
                if (key.ref() != null) {
                    bucket.keyRef(key.ref());
                }
                bucket.addMember(m);
                buckets.putIfAbsent(key.name(), bucket);
                bucketMembers.computeIfAbsent(key.name(), k -> new ArrayList<>()).add(m);
            }
        }
        for (Map.Entry<String, QuizableGroup> e : buckets.entrySet()) {
            nest(e.getValue(), bucketMembers.get(e.getKey()), facets, depth + 1);
        }
    }

    /**
     * Graft facet dimensions onto an existing root (e.g. a curated tree). Each
     * facet becomes a {@code FACET} child of the root; its values become
     * {@code BUCKET}s. Reference buckets carry their key entity.
     */
    public static QuizableGroup addFacets(
            QuizableGroup root,
            Collection<? extends Quizable> members,
            List<Facet> facets) {

        for (Facet facet : facets) {
            QuizableGroup facetNode = root.getOrCreateChild(facet.label()).role(Role.FACET);

            for (Quizable m : members) {
                if (m == null) {
                    continue;
                }
                for (FacetKey key : facet.keys().apply(m)) {
                    if (key == null || !key.isUsable()) {
                        continue;
                    }
                    QuizableGroup bucket =
                            facetNode.getOrCreateChild(key.name()).role(Role.BUCKET);
                    if (key.ref() != null) {
                        bucket.keyRef(key.ref());
                    }
                    bucket.addMember(m);
                }
            }
        }
        return root;
    }

    /**
     * Tag an existing (hand-built) tree by structure: the root is the
     * {@code UNIVERSE}, internal nodes are {@code FACET} headers (they hold the
     * union of their buckets), and leaves are selectable {@code BUCKET}s.
     */
    public static QuizableGroup assignRoles(QuizableGroup root) {
        assignRoles(root, 0);
        return root;
    }

    private static void assignRoles(QuizableGroup g, int depth) {
        boolean leaf = g.getChildren().isEmpty();
        g.role(depth == 0 ? Role.UNIVERSE : leaf ? Role.BUCKET : Role.FACET);
        for (QuizableGroup c : g.getChildren()) {
            assignRoles(c, depth + 1);
        }
    }

    /**
     * Re-parent a root's direct children under one named {@code FACET} node, for
     * a curated tree whose buckets sit flat under the root (a single dimension).
     * Then tag roles. Members stay reachable via recursion.
     */
    public static QuizableGroup wrapChildrenAsFacet(QuizableGroup root, String facetLabel) {
        List<QuizableGroup> kids = new ArrayList<>(root.getChildren());
        QuizableGroup facet = root.getOrCreateChild(facetLabel);
        for (QuizableGroup k : kids) {
            facet.addChild(k);
            root.getChildrenMap().remove(k.getIdentifier());
        }
        return assignRoles(root);
    }
}
