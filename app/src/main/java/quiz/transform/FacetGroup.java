package quiz.transform;

import objectview.Viewable;
import objectview.facet.Facet;
import objectview.facet.FacetGrouper;
import objectview.group.ViewableGroup.Role;
import objectview.group.ViewableGroupAdapter;

import java.util.Collection;
import java.util.List;

/**
 * A computed group whose bucket children are (re)produced by partitioning its parent scope's
 * members by a facet field — the facet-result counterpart of a hand-built manual group.
 *
 * <p>It remembers its RULE (the facet {@code field}) and its {@code memberClass} (the
 * reproduce universe — needed because a computed group can be empty before the first
 * {@link #reproduce}, so member-inference won't do). {@code reproduce(parentMembers)}
 * re-partitions via {@link FacetGrouper}, so the group stays fresh when the instance set
 * changes. Buckets are ordinary {@code quiz.ViewableGroup}s. Otherwise it behaves exactly
 * like a manual group (it IS a {@code ViewableGroup} via {@link ViewableGroupAdapter}).
 */
public final class FacetGroup extends ViewableGroupAdapter {

    private final String memberType;
    private final String field;

    public FacetGroup(String label, String memberType, String field) {
        super(label, label);
        this.memberType = memberType;
        this.field = field;
        role(Role.UNIVERSE);
    }

    /** The type (typeName) whose instances this group scopes/reproduces from — remembered
     *  explicitly because a computed group can be empty before the first reproduce, so
     *  member-inference won't do. (The Java class is the shared universe, not this.) */
    public String memberType() {
        return memberType;
    }

    /** The rule: the field this group buckets its members by. */
    public String field() {
        return field;
    }

    /** Rebuild children + members by partitioning {@code parentMembers} by {@link #field}
     *  (a facet over "Asia" reproduces against Asia's members — call parent before child). */
    public void reproduce(Collection<? extends Viewable> parentMembers) {
        clearChildren();
        clearMembers();
        // Keep the FacetGrouper structure intact (root -> facet-dimension node -> buckets):
        // that's exactly what the existing group-tree renderer expects — the dimension node
        // is the header, the buckets its rows.
        quiz.ViewableGroup tree = FacetGrouper.group(
                quiz.ViewableGroup::new, getDisplayName(), parentMembers,
                List.of(Facet.<Viewable>field(field)));
        role(tree.getRole());
        for (quiz.ViewableGroup child : tree.getChildren()) {
            putChild(child);
        }
        for (Viewable member : tree.getMembers()) {
            putMember(member);
        }
    }
}
