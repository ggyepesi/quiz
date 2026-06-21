package quiz.facet;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.QuizableGroup.Role;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
