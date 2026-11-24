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
    private final DomainModel domain;

    public ClassSubtypeGroup(String subtype, DomainModel domain) {
        super(subtype);
        this.subtype = java.util.Objects.requireNonNull(subtype, "subtype");
        this.domain = java.util.Objects.requireNonNull(domain, "domain");
        setRole(Role.BUCKET);
    }

    public String subtype() { return subtype; }

    /** Keep authored groups with the same caption distinct from this derived node. */
    @Override public String groupKey() { return "@class:" + subtype; }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers) {
        replaceMembers(parentMembers == null ? java.util.List.of()
                : parentMembers.stream()
                        .filter(member -> domain.isInstanceOf(member, subtype))
                        .toList());
    }

    @Override public String ruleDescription() {
        return "Instance is a member of class " + subtype;
    }
}
