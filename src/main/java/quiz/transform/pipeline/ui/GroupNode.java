package quiz.transform.pipeline.ui;

import quiz.transform.ui.DomainField;

/**
 * One grouping dimension in the view-steps group TREE — just the field it groups
 * by. Nesting is the node's POSITION in the {@code JTree}: a child drills down
 * within its parent's buckets, and siblings are parallel sub-dimensions. The tree
 * compiles to depth-tagged {@code GROUP_BY} steps (see {@code ViewStepsPanel}).
 */
public final class GroupNode {

    private DomainField field;

    public GroupNode(DomainField field) {
        this.field = field;
    }

    public DomainField field() {
        return field;
    }

    public void field(DomainField field) {
        this.field = field;
    }

    @Override
    public String toString() {
        return field == null ? "Groups" : field.path();
    }
}
