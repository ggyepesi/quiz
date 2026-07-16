package quiz.transform.ui;

import objectview.facet.Facet;

/**
 * The structural transform operations the workbench offers. Each compiles to a
 * real {@link quiz.transform.View} operation ({@link quiz.transform.ClassTransformPlan}
 * filter or a {@link Facet} grouping), so the preview runs the actual
 * engine. Grouping operations produce a new grouping class (a bucket per key with
 * its members) — the subdomain the result forms. Structural-first: value filters
 * refine members; projection (choose card fields) is the next op to add.
 */
public enum OperationKind {

    FILTER("Filter — keep where field == value", Nature.VIEW),
    GROUP_BY("Group by — bucket members by a field (a scalar keys by value, a reference by the entity)", Nature.VIEW),
    PROJECT_TO_CLASS("Project to class — a NEW class from the selected fields (fed back into the pool)", Nature.STRUCTURAL),
    JOIN("Join — a NEW class matching this class to another on a key (arguments from two classes)", Nature.STRUCTURAL);

    /**
     * The primary split. A {@code STRUCTURAL} op produces a new product CLASS,
     * mutating the domain schema (PROJECT / JOIN → a new Members entry). A
     * {@code VIEW} op only shapes the INSTANCES of an existing class (FILTER /
     * GROUP_BY) — it changes nothing in the schema and is a pipeline step.
     */
    public enum Nature { STRUCTURAL, VIEW }

    private final String label;
    private final Nature nature;

    OperationKind(String label, Nature nature) {
        this.label = label;
        this.nature = nature;
    }

    public Nature nature() {
        return nature;
    }

    /** True for ops that add a new class to the domain (PROJECT / JOIN). */
    public boolean producesClass() {
        return nature == Nature.STRUCTURAL;
    }

    @Override
    public String toString() {
        return label;
    }
}
