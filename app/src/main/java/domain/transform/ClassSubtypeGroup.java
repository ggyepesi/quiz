package domain.transform;

import domain.DomainModel;
import objectview.Viewable;

import java.util.Collection;

/**
 * Read-only group projection of one declared class below its direct base class.
 *
 * <p>The class hierarchy remains the source of truth. This node is rebuilt from that
 * hierarchy and the live instance pool, is never persisted as an authored group, and
 * gives every group-tree consumer the same visible subclass structure.</p>
 */
public final class ClassSubtypeGroup extends EditableGroup
        implements ProducedGroup, ConfiguredGroup {

    private final String subtype;
    private final java.util.function.BiPredicate<Viewable, String> membership;

    public ClassSubtypeGroup(String subtype, DomainModel domain) {
        this(subtype, java.util.Objects.requireNonNull(domain, "domain")::isInstanceOf);
    }

    ClassSubtypeGroup(
            String subtype,
            java.util.function.BiPredicate<Viewable, String> membership) {
        super(subtype);
        this.subtype = java.util.Objects.requireNonNull(subtype, "subtype");
        this.membership = java.util.Objects.requireNonNull(membership, "membership");
        setRole(Role.BUCKET);
    }

    public String subtype() { return subtype; }

    /** Keep authored groups with the same caption distinct from this derived node. */
    @Override public String groupKey() { return "@class:" + subtype; }

    /** A class hierarchy is useful only when its nested structure is immediately visible. */
    @Override public boolean initiallyExpanded() { return true; }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers) {
        replaceMembers(parentMembers == null ? java.util.List.of()
                : parentMembers.stream()
                        .filter(member -> membership.test(member, subtype))
                        .toList());
    }

    @Override public String ruleDescription() {
        return "Instance is a member of class " + subtype;
    }
}
