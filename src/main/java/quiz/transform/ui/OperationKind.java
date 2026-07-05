package quiz.transform.ui;

/**
 * The structural transform operations the workbench offers. Each compiles to a
 * real {@link quiz.transform.View} operation ({@link quiz.transform.ClassTransformPlan}
 * filter or a {@link quiz.facet.Facet} grouping), so the preview runs the actual
 * engine. Grouping operations produce a new grouping class (a bucket per key with
 * its members) — the subdomain the result forms. Structural-first: value filters
 * refine members; projection (choose card fields) is the next op to add.
 */
public enum OperationKind {

    FILTER("Filter — keep where field == value"),
    GROUP_BY_VALUE("Group by value — a scalar field's value → buckets"),
    GROUP_BY_REFERENCE("Group by reference (invert) — a member under each referenced entity"),
    PROJECT_TO_CLASS("Project to class — a NEW class from the selected fields (fed back into the pool)"),
    JOIN("Join — a NEW class matching this class to another on a key (arguments from two classes)");

    private final String label;

    OperationKind(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
